// Encoder edge cases the corpus cannot hold: what a shape must REFUSE.
//
//   jsc tools/avr32/encoder.js tools/avr32/test_encoder.js
//
// The corpus proves every instruction Ghidra assembled comes out byte for
// byte; nothing in it can show that an immediate outside a field is turned
// away rather than wrapped.  Each refusal here was once a wrong encoding:
// MOV's 21-bit immediate is sign-extended, so 0x100000 came back as
// -0x100000; SUB R13 is SUB SP and takes a word-scaled field, so "SUB
// R13,0x20" moved the stack four times as far; a triadic shift is two bits,
// and a larger one spilled into the reserved bits above.
var cases = [
  ['MOV R8,0xFFFFF', true], ['MOV R8,0x100000', false], ['MOV R8,0x1FFFFF', false],
  ['MOV R8,-0x100000', true], ['MOV R8,-0x100001', false], ['MOV R8,-0x1e4', true],
  ['SUB R13,0x20', 'SUB SP,0x20'], ['SUB R13,0x22', false],
  ['ADD R8,R8,R9 << 0x3', true], ['ADD R8,R8,R9 << 0x4', false], ['SUB R8,R8,R9 << 0xf', false],
];
var bad = 0;
function hex(b){ if(b===null) return 'null'; var s=''; for(var i=0;i<b.length;i++){var h=(b[i]&255).toString(16); s+=h.length<2?'0'+h:h;} return s; }
for (var i = 0; i < cases.length; i++) {
  var got = AVR32.encode(0x80010000, cases[i][0]), want = cases[i][1], ok;
  if (want === true) ok = got !== null;
  else if (want === false) ok = got === null;
  else ok = got !== null && hex(got) === hex(AVR32.encode(0x80010000, want));
  print((ok ? 'ok   ' : 'FAIL ') + cases[i][0] + ' -> ' + hex(got));
  if (!ok) bad++;
}
if (bad) throw new Error(bad + ' encoder check(s) failed');
print('encoder checks pass');
