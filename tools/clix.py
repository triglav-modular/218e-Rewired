"""The CLIX fills from the 208 Clockwork Card, as 32-step masks.

Generated from Clockwork_Code/clix.h; bit 0 is step 1.  Twenty-two fills
from a single hit to every step, which is the bank knob 2 selects from
when it is set to patterns.
"""

CLIX = [
    0x00000001,
    0x00010001,
    0x00010101,
    0x01010101,
    0x01010111,
    0x01110111,
    0x11110111,
    0x11111111,
    0x11111115,
    0x11151115,
    0x15151515,
    0x15551555,
    0x55555555,
    0x55575557,
    0x55575757,
    0x57575757,
    0x57775777,
    0x777f777f,
    0x7f7f7f7f,
    0x7f7f7fff,
    0x7fff7fff,
    0xffffffff,
]
