package main

// EV1527 frames are fixed at 24 bits: a 20-bit address plus a 4-bit key.
// Frames that do not match this shape are dropped at ingest instead of being
// normalized, because a weak frame decoded under the wrong timing template
// still looks like a valid 24-bit code.
const (
	ev1527Protocol = 1
	ev1527Length   = 24
	ev1527KeyBits  = 4
	ev1527MaxValue = (1 << ev1527Length) - 1
	ev1527KeyMask  = (1 << ev1527KeyBits) - 1
)

func ev1527Matches(protocol, length int) bool {
	return protocol == ev1527Protocol && length == ev1527Length
}

func ev1527Valid(value int64) bool {
	return value >= 0 && value <= ev1527MaxValue
}

func ev1527Address(value int64) int64 {
	return value >> ev1527KeyBits
}

func ev1527Key(value int64) int {
	return int(value & ev1527KeyMask)
}

// ev1527SameAddress reports whether both codes belong to one remote.
func ev1527SameAddress(a, b int64) bool {
	return ev1527Address(a) == ev1527Address(b)
}
