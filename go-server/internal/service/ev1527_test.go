package service

import "testing"

func TestEv1527Gating(t *testing.T) {
	cases := []struct {
		name     string
		length   int
		value    int64
		accepted bool
		valid    bool
	}{
		{"24-bit frame", 24, 1714739, true, true},
		{"23-bit frame", 23, 1714739, false, true},
		{"25-bit frame", 25, 1714739, false, true},
		{"zero value", 24, 0, true, true},
		{"max value", 24, 0xFFFFFF, true, true},
		{"negative value", 24, -1, true, false},
		{"above max", 24, 0x1000000, true, false},
	}

	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := ev1527AcceptsLength(testCase.length); got != testCase.accepted {
				t.Fatalf(
					"acceptsLength(%d) = %t, want %t",
					testCase.length, got, testCase.accepted,
				)
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
