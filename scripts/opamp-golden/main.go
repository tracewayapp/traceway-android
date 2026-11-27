// Command opamp-golden checks the Android SDK's hand-written OpAMP codec
// (traceway/src/main/java/com/tracewayapp/traceway/opamp) against opamp-go's
// generated protobufs, the types the Traceway backend decodes and encodes
// with (opamp-go v0.25.0, as in traceway/backend/go.mod).
//
// From this directory (scripts/opamp-golden):
//
//	GOFLAGS=-mod=mod go run . golden > \
//	  ../../traceway/src/test/java/com/tracewayapp/traceway/opamp/OpampGoldens.kt
//
// regenerates the goldens: opamp-go's bytes (proto.Marshal, deterministic)
// for every message below. OpampInteropTest encodes the same AgentToServer
// messages and must produce the same bytes, and decodes the ServerToAgent
// ones.
//
// The other direction: run the Kotlin tests with TRACEWAY_OPAMP_GOLDEN_OUT
// set to a file, where they write a line "a2s|s2a <name> <hex>" for each
// message the Kotlin encoder produced, then
//
//	GOFLAGS=-mod=mod go run . check <file>
//
// decodes every line with proto.Unmarshal, prints it as prototext and exits 1
// unless it equals the message of that name (and, for AgentToServer, unless
// the bytes are opamp-go's own). `go run . decode a2s|s2a <hex>` prints one
// message.
package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/open-telemetry/opamp-go/protobufs"
	"google.golang.org/protobuf/encoding/prototext"
	"google.golang.org/protobuf/proto"
)

type named struct {
	name string
	msg  proto.Message
}

var (
	uid    = mustHex("0195f2a0000070008000000000000001")
	newUid = mustHex("ffeeddccbbaa99887766554433221100")
)

// The settings document and hash the backend would send.
const settingsDocument = `{"schemaVersion":1,"replay":{"mode":"stream"}}`

const (
	sdkCapabilities = uint64(protobufs.AgentCapabilities_AgentCapabilities_ReportsStatus |
		protobufs.AgentCapabilities_AgentCapabilities_AcceptsRemoteConfig |
		protobufs.AgentCapabilities_AgentCapabilities_ReportsRemoteConfig |
		protobufs.AgentCapabilities_AgentCapabilities_ReportsHeartbeat)
	// What opampcontrollers advertises.
	serverCapabilities = uint64(protobufs.ServerCapabilities_ServerCapabilities_AcceptsStatus |
		protobufs.ServerCapabilities_ServerCapabilities_OffersRemoteConfig |
		protobufs.ServerCapabilities_ServerCapabilities_OffersConnectionSettings)
)

var customCapabilities = []string{
	"io.traceway.sdk.buffer/v1",
	"io.traceway.sdk.capture/v1",
	"io.traceway.sdk.sequence-triggers/v1",
}

func mustHex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

func sha(s string) []byte {
	h := sha256.Sum256([]byte(s))
	return h[:]
}

func kv(key string, value *protobufs.AnyValue) *protobufs.KeyValue {
	return &protobufs.KeyValue{Key: key, Value: value}
}

func str(s string) *protobufs.AnyValue {
	return &protobufs.AnyValue{Value: &protobufs.AnyValue_StringValue{StringValue: s}}
}

func boolean(b bool) *protobufs.AnyValue {
	return &protobufs.AnyValue{Value: &protobufs.AnyValue_BoolValue{BoolValue: b}}
}

func integer(i int64) *protobufs.AnyValue {
	return &protobufs.AnyValue{Value: &protobufs.AnyValue_IntValue{IntValue: i}}
}

func double(f float64) *protobufs.AnyValue {
	return &protobufs.AnyValue{Value: &protobufs.AnyValue_DoubleValue{DoubleValue: f}}
}

func array(items ...string) *protobufs.AnyValue {
	values := []*protobufs.AnyValue{}
	for _, item := range items {
		values = append(values, str(item))
	}
	return &protobufs.AnyValue{Value: &protobufs.AnyValue_ArrayValue{ArrayValue: &protobufs.ArrayValue{Values: values}}}
}

// heartbeatOffer is the connection settings offer the backend sends: the
// heartbeat and the hash of its decimal text.
func heartbeatOffer(seconds uint64) *protobufs.ConnectionSettingsOffers {
	return &protobufs.ConnectionSettingsOffers{
		Hash:  sha(strconv.FormatUint(seconds, 10)),
		Opamp: &protobufs.OpAMPConnectionSettings{HeartbeatIntervalSeconds: seconds},
	}
}

func tracewayConfig(document string, hash []byte) *protobufs.AgentRemoteConfig {
	return &protobufs.AgentRemoteConfig{
		Config: &protobufs.AgentConfigMap{ConfigMap: map[string]*protobufs.AgentConfigObject{
			"traceway": {Body: []byte(document), ContentType: "application/json"},
		}},
		ConfigHash: hash,
	}
}

// agentToServer: messages the Kotlin encoder must write exactly as opamp-go.
func agentToServer() []named {
	return []named{
		// The browser and Flutter SDKs' shared golden.
		{"shared", &protobufs.AgentToServer{
			InstanceUid: uid,
			SequenceNum: 3,
			AgentDescription: &protobufs.AgentDescription{
				IdentifyingAttributes: []*protobufs.KeyValue{kv("service.name", str("shop-web"))},
				NonIdentifyingAttributes: []*protobufs.KeyValue{
					kv("service.version", str("2.3.1")),
					kv("browser.mobile", boolean(true)),
					kv("seats", integer(12)),
					kv("ratio", double(0.25)),
				},
			},
			Capabilities: sdkCapabilities,
			RemoteConfigStatus: &protobufs.RemoteConfigStatus{
				LastRemoteConfigHash: mustHex("abcd"),
				Status:               protobufs.RemoteConfigStatuses_RemoteConfigStatuses_APPLIED,
			},
			CustomCapabilities: &protobufs.CustomCapabilities{Capabilities: customCapabilities[:2]},
		}},
		// An Android SDK's first poll.
		{"firstPoll", &protobufs.AgentToServer{
			InstanceUid: uid,
			AgentDescription: &protobufs.AgentDescription{
				IdentifyingAttributes: []*protobufs.KeyValue{
					kv("service.name", str("shop-android")),
					kv("service.instance.id", str("0195f2a0-0000-7000-8000-000000000001")),
				},
				NonIdentifyingAttributes: []*protobufs.KeyValue{
					kv("service.version", str("4.1.0")),
					kv("os.name", str("Android")),
					kv("os.version", str("14")),
					kv("device.manufacturer", str("Google")),
					kv("device.model.name", str("Pixel 8")),
					kv("app.build", integer(412)),
					kv("session.id", str("0195f2a1-1111-7222-8333-444455556666")),
				},
			},
			Capabilities:       sdkCapabilities,
			CustomCapabilities: &protobufs.CustomCapabilities{Capabilities: customCapabilities},
		}},
		// The poll right after a config was applied.
		{"applied", &protobufs.AgentToServer{
			InstanceUid: uid,
			SequenceNum: 1,
			AgentDescription: &protobufs.AgentDescription{
				IdentifyingAttributes: []*protobufs.KeyValue{kv("service.name", str("shop-android"))},
			},
			Capabilities: sdkCapabilities,
			RemoteConfigStatus: &protobufs.RemoteConfigStatus{
				LastRemoteConfigHash: sha(settingsDocument),
				Status:               protobufs.RemoteConfigStatuses_RemoteConfigStatuses_APPLIED,
			},
			CustomCapabilities: &protobufs.CustomCapabilities{Capabilities: customCapabilities},
		}},
		// A config that could not be applied, with a non-ASCII message.
		{"failed", &protobufs.AgentToServer{
			InstanceUid: uid,
			SequenceNum: 2,
			AgentDescription: &protobufs.AgentDescription{
				IdentifyingAttributes: []*protobufs.KeyValue{kv("service.name", str("shop-android"))},
			},
			Capabilities: sdkCapabilities,
			RemoteConfigStatus: &protobufs.RemoteConfigStatus{
				LastRemoteConfigHash: mustHex("09"),
				Status:               protobufs.RemoteConfigStatuses_RemoteConfigStatuses_FAILED,
				ErrorMessage:         "bad: schemaVersion 9 ≠ 1 ✗",
			},
		}},
		// Every kind of attribute value, at its edges.
		{"values", &protobufs.AgentToServer{
			InstanceUid: uid,
			SequenceNum: 300,
			AgentDescription: &protobufs.AgentDescription{
				IdentifyingAttributes: []*protobufs.KeyValue{kv("service.name", str("ünïcødé 🚀"))},
				NonIdentifyingAttributes: []*protobufs.KeyValue{
					kv("flag", boolean(false)),
					kv("n", integer(0)),
					kv("big", integer(1<<40)),
					kv("max", integer(math.MaxInt64)),
					kv("neg", double(-5)),
					kv("zero", double(0)),
					kv("half", double(-0.5)),
					kv("float", double(1.5)),
					kv("list", array("a", "b")),
					kv("none", array()),
					kv("empty", str("")),
					kv("", str("keyless")),
				},
			},
			Capabilities: sdkCapabilities,
		}},
		// A description without attributes is still sent: left out, the
		// server would answer ReportFullState.
		{"emptyDescription", &protobufs.AgentToServer{
			InstanceUid:      uid,
			SequenceNum:      5,
			AgentDescription: &protobufs.AgentDescription{},
			Capabilities:     sdkCapabilities,
		}},
		{"minimal", &protobufs.AgentToServer{InstanceUid: uid}},
	}
}

// serverToAgent: what the backend answers, for the Kotlin decoder.
func serverToAgent() []named {
	return []named{
		// The browser and Flutter SDKs' shared goldens.
		{"shared", &protobufs.ServerToAgent{
			InstanceUid:  uid,
			RemoteConfig: tracewayConfig(`{"schemaVersion":1,"replay":{"mode":"stream"}}`, mustHex("010203")),
			ConnectionSettings: &protobufs.ConnectionSettingsOffers{
				Hash:  mustHex("09"),
				Opamp: &protobufs.OpAMPConnectionSettings{HeartbeatIntervalSeconds: 300},
			},
			Flags:               uint64(protobufs.ServerToAgentFlags_ServerToAgentFlags_ReportFullState),
			Capabilities:        serverCapabilities,
			AgentIdentification: &protobufs.AgentIdentification{NewInstanceUid: newUid},
		}},
		{"sharedError", &protobufs.ServerToAgent{
			ErrorResponse: &protobufs.ServerErrorResponse{
				Type:         protobufs.ServerErrorResponseType_ServerErrorResponseType_Unavailable,
				ErrorMessage: "busy",
				Details: &protobufs.ServerErrorResponse_RetryInfo{
					RetryInfo: &protobufs.RetryInfo{RetryAfterNanoseconds: uint64(time.Minute)},
				},
			},
		}},
		// A poll answered with the settings document.
		{"remoteConfig", &protobufs.ServerToAgent{
			InstanceUid:        uid,
			RemoteConfig:       tracewayConfig(settingsDocument, sha(settingsDocument)),
			ConnectionSettings: heartbeatOffer(300),
			Capabilities:       serverCapabilities,
		}},
		// The config is already applied: only the heartbeat.
		{"heartbeat", &protobufs.ServerToAgent{
			InstanceUid:        uid,
			ConnectionSettings: heartbeatOffer(60),
			Capabilities:       serverCapabilities,
		}},
		// The poll came without a description.
		{"reportFullState", &protobufs.ServerToAgent{
			InstanceUid:  uid,
			Flags:        uint64(protobufs.ServerToAgentFlags_ServerToAgentFlags_ReportFullState),
			Capabilities: serverCapabilities,
		}},
		// The SDK config could not be loaded (sent with HTTP 503).
		{"unavailable", &protobufs.ServerToAgent{
			InstanceUid:  uid,
			Capabilities: serverCapabilities,
			ErrorResponse: &protobufs.ServerErrorResponse{
				Type:         protobufs.ServerErrorResponseType_ServerErrorResponseType_Unavailable,
				ErrorMessage: "remote config is temporarily unavailable",
				Details: &protobufs.ServerErrorResponse_RetryInfo{
					RetryInfo: &protobufs.RetryInfo{RetryAfterNanoseconds: uint64(60 * time.Second)},
				},
			},
		}},
		// A malformed poll (sent with HTTP 400).
		{"badRequest", &protobufs.ServerToAgent{
			ErrorResponse: &protobufs.ServerErrorResponse{
				Type:         protobufs.ServerErrorResponseType_ServerErrorResponseType_BadRequest,
				ErrorMessage: "instance_uid must be 16 bytes and match the OpAMP-Instance-UID header",
			},
		}},
		// The client asked for an instance uid.
		{"newInstanceUid", &protobufs.ServerToAgent{
			InstanceUid:         uid,
			ConnectionSettings:  heartbeatOffer(300),
			Capabilities:        serverCapabilities,
			AgentIdentification: &protobufs.AgentIdentification{NewInstanceUid: newUid},
		}},
		// Two config documents; map entries in key order.
		{"twoConfigs", &protobufs.ServerToAgent{
			InstanceUid: uid,
			RemoteConfig: &protobufs.AgentRemoteConfig{
				Config: &protobufs.AgentConfigMap{ConfigMap: map[string]*protobufs.AgentConfigObject{
					"traceway": {Body: []byte(`{"schemaVersion":1}`), ContentType: "application/json"},
					"other":    {ContentType: "text/plain"},
				}},
				ConfigHash: mustHex("0a0b0c"),
			},
		}},
		// Everything the client does not read, around a heartbeat it does.
		{"ignoredFields", &protobufs.ServerToAgent{
			InstanceUid: uid,
			RemoteConfig: &protobufs.AgentRemoteConfig{
				Config: &protobufs.AgentConfigMap{ConfigMap: map[string]*protobufs.AgentConfigObject{
					"traceway": {Body: []byte(`{}`), ContentType: "application/json", Role: "primary"},
				}},
				ConfigHash: mustHex("0d"),
			},
			ConnectionSettings: &protobufs.ConnectionSettingsOffers{
				Hash: mustHex("ee"),
				Opamp: &protobufs.OpAMPConnectionSettings{
					DestinationEndpoint:      "https://elsewhere.test/v1/opamp",
					Headers:                  &protobufs.Headers{Headers: []*protobufs.Header{{Key: "k", Value: "v"}}},
					HeartbeatIntervalSeconds: 45,
				},
				OwnMetrics: &protobufs.TelemetryConnectionSettings{DestinationEndpoint: "https://metrics.test"},
			},
			PackagesAvailable: &protobufs.PackagesAvailable{
				Packages: map[string]*protobufs.PackageAvailable{
					"p": {Type: protobufs.PackageType_PackageType_Addon, Version: "1.0", Hash: mustHex("01")},
				},
				AllPackagesHash: mustHex("02"),
			},
			Flags:              3,
			Capabilities:       0x7f,
			Command:            &protobufs.ServerToAgentCommand{},
			CustomCapabilities: &protobufs.CustomCapabilities{Capabilities: []string{"x.y/v1"}},
			CustomMessage:      &protobufs.CustomMessage{Capability: "x.y/v1", Type: "t", Data: []byte{1, 2}},
		}},
	}
}

// helperServerToAgent: what OpampInteropTest builds with the Kotlin tests'
// encodeServerToAgent, which mock servers in the client tests use.
func helperServerToAgent() []named {
	return []named{
		{"helperAll", &protobufs.ServerToAgent{
			InstanceUid:        uid,
			RemoteConfig:       tracewayConfig(`{"schemaVersion":1}`, mustHex("0a0b")),
			ConnectionSettings: &protobufs.ConnectionSettingsOffers{Opamp: &protobufs.OpAMPConnectionSettings{HeartbeatIntervalSeconds: 120}},
			Flags:              uint64(protobufs.ServerToAgentFlags_ServerToAgentFlags_ReportFullState),
			Capabilities:       serverCapabilities,
			AgentIdentification: &protobufs.AgentIdentification{
				NewInstanceUid: newUid,
			},
		}},
		{"helperError", serverToAgent()[1].msg},
	}
}

func marshal(m proto.Message) []byte {
	out, err := proto.MarshalOptions{Deterministic: true}.Marshal(m)
	if err != nil {
		panic(err)
	}
	return out
}

func constName(prefix, name string) string {
	var b strings.Builder
	b.WriteString(prefix)
	for i, r := range name {
		if unicode.IsUpper(r) && i > 0 {
			b.WriteByte('_')
		}
		b.WriteRune(unicode.ToUpper(r))
	}
	return b.String()
}

func golden() {
	fmt.Println(`package com.tracewayapp.traceway.opamp

// Generated by opamp-golden (scripts/opamp-golden/main.go) from
// opamp-go v0.25.0's generated types, proto.Marshal deterministic. Do not
// edit; see main.go there to regenerate.
internal object OpampGoldens {`)
	for _, m := range agentToServer() {
		fmt.Printf("    const val %s =\n        \"%s\"\n", constName("A2S_", m.name), hex.EncodeToString(marshal(m.msg)))
	}
	for _, m := range serverToAgent() {
		fmt.Printf("    const val %s =\n        \"%s\"\n", constName("S2A_", m.name), hex.EncodeToString(marshal(m.msg)))
	}
	fmt.Println("}")
}

func newOf(kind string) proto.Message {
	if kind == "a2s" {
		return &protobufs.AgentToServer{}
	}
	return &protobufs.ServerToAgent{}
}

func text(m proto.Message) string {
	return prototext.MarshalOptions{Multiline: true, Indent: "  "}.Format(m)
}

func check(path string) {
	expected := map[string]proto.Message{}
	for _, m := range agentToServer() {
		expected["a2s "+m.name] = m.msg
	}
	for _, m := range helperServerToAgent() {
		expected["s2a "+m.name] = m.msg
	}
	file, err := os.Open(path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	defer file.Close()
	line := regexp.MustCompile(`^(a2s|s2a) (\S+) ([0-9a-f]*)$`)
	seen := map[string]bool{}
	failed := false
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 1<<20), 1<<20)
	for scanner.Scan() {
		parts := line.FindStringSubmatch(strings.TrimSpace(scanner.Text()))
		if parts == nil {
			continue
		}
		key := parts[1] + " " + parts[2]
		seen[key] = true
		got := newOf(parts[1])
		bytes := mustHex(parts[3])
		if err := proto.Unmarshal(bytes, got); err != nil {
			fmt.Printf("FAIL %s: %v\n", key, err)
			failed = true
			continue
		}
		fmt.Printf("== %s (%d bytes)\n%s", key, len(bytes), text(got))
		want, ok := expected[key]
		switch {
		case !ok:
			fmt.Printf("FAIL %s: no expected message of that name\n", key)
			failed = true
		case !proto.Equal(got, want):
			fmt.Printf("FAIL %s: decodes to something else; want\n%s", key, text(want))
			failed = true
		case parts[1] == "a2s" && hex.EncodeToString(marshal(want)) != parts[3]:
			fmt.Printf("FAIL %s: equal, but not opamp-go's bytes %x\n", key, marshal(want))
			failed = true
		default:
			fmt.Printf("ok   %s\n", key)
		}
	}
	for key := range expected {
		if !seen[key] {
			fmt.Printf("FAIL %s: missing from %s\n", key, path)
			failed = true
		}
	}
	if failed {
		os.Exit(1)
	}
	fmt.Printf("all %d messages match\n", len(expected))
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: opamp-golden golden | check <file> | decode a2s|s2a <hex>")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "golden":
		golden()
	case "check":
		check(os.Args[2])
	case "decode":
		m := newOf(os.Args[2])
		if err := proto.Unmarshal(mustHex(os.Args[3]), m); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fmt.Print(text(m))
	default:
		fmt.Fprintln(os.Stderr, "unknown command", os.Args[1])
		os.Exit(2)
	}
}
