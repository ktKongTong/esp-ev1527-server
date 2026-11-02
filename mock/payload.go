package main

import (
	"bytes"
	"crypto/rand"
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
)

// RFCode is the exact payload published on <prefix>/315toMQTT. Field order is
// significant for the JSON contract: value, protocol, length, delay.
type RFCode struct {
	Value    uint64 `json:"value"`
	Protocol int    `json:"protocol"`
	Length   int    `json:"length"`
	Delay    int    `json:"delay"`
}

// txCommand is the payload received on <prefix>/commands/MQTTto315. Pointers
// let us distinguish "field omitted" from a legitimate zero value.
type txCommand struct {
	Value    *uint64 `json:"value"`
	Protocol *int    `json:"protocol"`
	Length   *int    `json:"length"`
	Repeat   *int    `json:"repeat"`
}

type deviceButton struct {
	Label string
	On    uint64
	Off   uint64
}

// knownDevices is intentionally tiny; the contract only predefines device 1.
// Add entries here if the business side needs more physical buttons.
var knownDevices = map[int]deviceButton{
	1: {Label: "1号设备", On: 1714739, Off: 1714740},
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

func parseTXCommand(raw []byte) (txCommand, error) {
	var cmd txCommand
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&cmd); err != nil {
		return cmd, err
	}
	if cmd.Value == nil {
		return cmd, errors.New("payload 缺少 value 字段")
	}
	if cmd.Repeat != nil && *cmd.Repeat < 0 {
		return cmd, errors.New("repeat 不能为负数")
	}
	if cmd.Protocol != nil && *cmd.Protocol <= 0 {
		return cmd, errors.New("protocol 必须是正整数")
	}
	if cmd.Length != nil && *cmd.Length <= 0 {
		return cmd, errors.New("length 必须是正整数")
	}
	return cmd, nil
}

func (c txCommand) repeatCount() int {
	if c.Repeat == nil {
		return 1
	}
	return *c.Repeat
}

// formatHex renders 24-bit RF values in the console's uppercase form
// (0x1A2A33 for 1714739). Note: the example in the task description says
// 0x1A2B33, which is actually 1714995; we print the mathematically correct
// conversion.
func formatHex(value uint64) string {
	return fmt.Sprintf("0x%06X", value)
}

func randomRFCode() (uint64, error) {
	var b [3]byte
	for i := 0; i < 64; i++ {
		if _, err := rand.Read(b[:]); err != nil {
			return 0, err
		}
		v := uint64(b[0])<<16 | uint64(b[1])<<8 | uint64(b[2])
		if v != 0 && !isKnownRFCode(v) {
			return v, nil
		}
	}
	return 0, errors.New("无法生成随机 24 位码")
}

func isKnownRFCode(v uint64) bool {
	for _, d := range knownDevices {
		if v == d.On || v == d.Off {
			return true
		}
	}
	return false
}

func lookupDevice(n int) (deviceButton, bool) {
	d, ok := knownDevices[n]
	return d, ok
}
