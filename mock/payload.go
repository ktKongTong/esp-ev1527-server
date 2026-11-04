package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
)

const (
	rfProtocol       = 1
	rfBitLength      = 24
	rfMax24Bit       = (1 << 24) - 1
	rfReceiveDelayMS = 330
	rfQoS            = 1

	// rfMaxLength / rfMaxRepeat / rfMaxPressFrames bound HTTP/CLI input so a
	// malformed request cannot turn into an unbounded publish loop.
	rfMaxLength      = 32
	rfMaxRepeat      = 50
	rfMaxPressFrames = 20
)

// RFCode is the exact payload published on <prefix>/315toMQTT. Field order is
// significant for the JSON contract: value, protocol, length, delay.
type RFCode struct {
	Value    uint64 `json:"value"`
	Protocol int    `json:"protocol"`
	Length   int    `json:"length"`
	Delay    int    `json:"delay"`
}

// TXFrame is one simulated 315MHz transmission burst. Its field order matches
// the command payload accepted on <prefix>/commands/MQTTto315.
type TXFrame struct {
	Value    uint64 `json:"value"`
	Protocol int    `json:"protocol"`
	Length   int    `json:"length"`
	Repeat   int    `json:"repeat"`
}

// txCommand is the payload received on <prefix>/commands/MQTTto315. Pointers
// let us distinguish "field omitted" from a legitimate zero value.
type txCommand struct {
	Value    *uint64 `json:"value"`
	Protocol *int    `json:"protocol"`
	Length   *int    `json:"length"`
	Repeat   *int    `json:"repeat"`
}

func newRFCode(value uint64) RFCode {
	return RFCode{
		Value:    value,
		Protocol: rfProtocol,
		Length:   rfBitLength,
		Delay:    rfReceiveDelayMS,
	}
}

func (c RFCode) marshal() ([]byte, error) {
	return json.Marshal(c)
}

func (f TXFrame) marshal() ([]byte, error) {
	return json.Marshal(f)
}

// validateRFParams rejects values that cannot be represented by the configured
// protocol/length pair. The mock fixes protocol 1 and 24 bits for the contract,
// but the gate accepts other positive widths so malformed frames are reported
// instead of being silently truncated.
func validateRFParams(value uint64, protocol, length int) error {
	if protocol <= 0 {
		return errors.New("protocol 必须是正整数")
	}
	if length < 1 || length > rfMaxLength {
		return fmt.Errorf("length 必须在 1-%d 之间", rfMaxLength)
	}
	if value >= uint64(1)<<uint(length) {
		return fmt.Errorf("value=%d 超出 length=%d 的范围", value, length)
	}
	return nil
}

func parseTXCommand(raw []byte) (txCommand, error) {
	var cmd txCommand
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&cmd); err != nil {
		return cmd, err
	}
	if cmd.Value == nil {
		return cmd, errors.New("payload 缺少 value 字段")
	}
	if cmd.Repeat != nil && (*cmd.Repeat < 1 || *cmd.Repeat > rfMaxRepeat) {
		return cmd, fmt.Errorf("repeat 必须在 1-%d 之间", rfMaxRepeat)
	}
	if cmd.Protocol != nil && *cmd.Protocol <= 0 {
		return cmd, errors.New("protocol 必须是正整数")
	}
	length := rfBitLength
	if cmd.Length != nil {
		length = *cmd.Length
	}
	protocol := rfProtocol
	if cmd.Protocol != nil {
		protocol = *cmd.Protocol
	}
	if err := validateRFParams(*cmd.Value, protocol, length); err != nil {
		return cmd, err
	}
	return cmd, nil
}

func (c txCommand) repeatCount() int {
	if c.Repeat == nil {
		return 1
	}
	return *c.Repeat
}

func (c txCommand) frame() TXFrame {
	protocol, length := rfProtocol, rfBitLength
	if c.Protocol != nil {
		protocol = *c.Protocol
	}
	if c.Length != nil {
		length = *c.Length
	}
	return TXFrame{
		Value:    *c.Value,
		Protocol: protocol,
		Length:   length,
		Repeat:   c.repeatCount(),
	}
}

// formatHex renders 24-bit RF values in the console's uppercase form
// (0x1A2A33 for 1714739). Note: the example in the task description says
// 0x1A2B33, which is actually 1714995; we print the mathematically correct
// conversion.
func formatHex(value uint64) string {
	return fmt.Sprintf("0x%06X", value)
}
