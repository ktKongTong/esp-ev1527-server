package main

import "testing"

func TestEv1527Gating(t *testing.T) {
	cases := []struct {
		name     string
		protocol int
		length   int
		value    int64
		matches  bool
		valid    bool
	}{
		{"valid frame", 1, 24, 1714739, true, true},
		{"wrong protocol", 22, 24, 392008, false, true},
		{"wrong length", 1, 23, 1714739, false, true},
		{"zero value", 1, 24, 0, true, true},
		{"max value", 1, 24, 0xFFFFFF, true, true},
		{"negative value", 1, 24, -1, true, false},
		{"above max", 1, 24, 0x1000000, true, false},
	}

	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := ev1527Matches(testCase.protocol, testCase.length); got != testCase.matches {
				t.Fatalf("matches(%d, %d) = %t, want %t", testCase.protocol, testCase.length, got, testCase.matches)
			}
			if got := ev1527Valid(testCase.value); got != testCase.valid {
				t.Fatalf("valid(%d) = %t, want %t", testCase.value, got, testCase.valid)
			}
		})
	}
}

func TestEv1527AddressAndKey(t *testing.T) {
	const on int64 = 1714739
	const off int64 = 1714740
	if !ev1527SameAddress(on, off) {
		t.Fatalf("expected %d and %d to share an address", on, off)
	}
	if ev1527Key(on) == ev1527Key(off) {
		t.Fatalf("expected different keys, got %d", ev1527Key(on))
	}
	if ev1527Address(on) != on>>4 {
		t.Fatalf("address mismatch: %d", ev1527Address(on))
	}
}
