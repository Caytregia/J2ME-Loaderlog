/*
 * Safety net for the dexer: dx simulates every method with the max_stack / max_locals
 * written in the class file and fails with "stack: overflow" when they are too small.
 * After the ASM pass this class recomputes both values by walking the bytecode and
 * raises them in place when needed. Class size doesn't change. On any problem the
 * original bytes are returned untouched.
 */

package org.microemu.android.asm;

import java.util.ArrayList;
import java.util.Arrays;

final class StackFixer {
	/** TABLE[op] = {pop, push} in stack slots (long/double take two), null if variable. */
	private static final int[][] TABLE = buildTable();

	private StackFixer() {
	}

	static byte[] fix(byte[] data) {
		try {
			return doFix(data);
		} catch (Throwable t) {
			return data;
		}
	}

	private static int[] r(int from, int toInclusive) {
		int[] a = new int[toInclusive - from + 1];
		for (int i = 0; i < a.length; i++) {
			a[i] = from + i;
		}
		return a;
	}

	private static void s(int[][] t, int pop, int push, int... ops) {
		for (int op : ops) {
			t[op] = new int[]{pop, push};
		}
	}

	private static int[][] buildTable() {
		int[][] t = new int[256][];
		s(t, 0, 0, 0);
		s(t, 0, 1, 1);
		s(t, 0, 1, r(2, 8));
		s(t, 0, 2, 9, 10);
		s(t, 0, 1, 11, 12, 13);
		s(t, 0, 2, 14, 15);
		s(t, 0, 1, 16, 17, 18, 19);
		s(t, 0, 2, 20);
		s(t, 0, 1, 21, 23, 25);
		s(t, 0, 2, 22, 24);
		s(t, 0, 1, r(26, 29));
		s(t, 0, 2, r(30, 33));
		s(t, 0, 1, r(34, 37));
		s(t, 0, 2, r(38, 41));
		s(t, 0, 1, r(42, 45));
		s(t, 2, 1, 46, 48, 50, 51, 52, 53);
		s(t, 2, 2, 47, 49);
		s(t, 1, 0, 54, 56, 58);
		s(t, 2, 0, 55, 57);
		s(t, 1, 0, r(59, 62));
		s(t, 2, 0, r(63, 66));
		s(t, 1, 0, r(67, 70));
		s(t, 2, 0, r(71, 74));
		s(t, 1, 0, r(75, 78));
		s(t, 3, 0, 79, 81, 83, 84, 85, 86);
		s(t, 4, 0, 80, 82);
		s(t, 1, 0, 87);
		s(t, 2, 0, 88);
		s(t, 1, 2, 89);
		s(t, 2, 3, 90);
		s(t, 3, 4, 91);
		s(t, 2, 4, 92);
		s(t, 3, 5, 93);
		s(t, 4, 6, 94);
		s(t, 2, 2, 95);
		int[] bases = {96, 100, 104, 108, 112}; // add, sub, mul, div, rem
		for (int base : bases) {
			s(t, 2, 1, base, base + 2);
			s(t, 4, 2, base + 1, base + 3);
		}
		s(t, 1, 1, 116, 118);
		s(t, 2, 2, 117, 119);
		s(t, 2, 1, 120, 122, 124);
		s(t, 3, 2, 121, 123, 125);
		s(t, 2, 1, 126, 128, 130);
		s(t, 4, 2, 127, 129, 131);
		s(t, 0, 0, 132);
		s(t, 1, 2, 133, 135, 140, 141);
		s(t, 1, 1, 134, 139, 145, 146, 147);
		s(t, 2, 1, 136, 137, 142, 144);
		s(t, 2, 2, 138, 143);
		s(t, 4, 1, 148);
		s(t, 2, 1, 149, 150);
		s(t, 4, 1, 151, 152);
		s(t, 1, 0, r(153, 158));
		s(t, 2, 0, r(159, 166));
		s(t, 0, 0, 167);
		s(t, 0, 1, 168);
		s(t, 0, 0, 169);
		s(t, 1, 0, 170, 171);
		s(t, 1, 0, 172, 174, 176);
		s(t, 2, 0, 173, 175);
		s(t, 0, 0, 177);
		s(t, 0, 1, 187);
		s(t, 1, 1, 188, 189, 190);
		s(t, 1, 0, 191);
		s(t, 1, 1, 192, 193);
		s(t, 1, 0, 194, 195);
		s(t, 1, 0, 198, 199);
		s(t, 0, 0, 200);
		s(t, 0, 1, 201);
		return t;
	}

	private static int u1(byte[] b, int p) {
		return b[p] & 0xFF;
	}

	private static int u2(byte[] b, int p) {
		return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
	}

	private static int s2(byte[] b, int p) {
		return (short) u2(b, p);
	}

	private static int s4(byte[] b, int p) {
		return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
				| ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
	}

	private static void put2(byte[] b, int p, int v) {
		b[p] = (byte) (v >> 8);
		b[p + 1] = (byte) v;
	}

	private static int skipAttrs(byte[] b, int pos) {
		int count = u2(b, pos);
		pos += 2;
		for (int k = 0; k < count; k++) {
			pos += 6 + s4(b, pos + 2);
		}
		return pos;
	}

	private static byte[] doFix(byte[] data) {
		if (data.length < 10 || s4(data, 0) != 0xCAFEBABE) {
			return data;
		}
		int cnt = u2(data, 8);
		String[] utf8 = new String[cnt];
		int[] refNat = new int[cnt];
		int[] natDesc = new int[cnt];
		int i = 10;
		for (int n = 1; n < cnt; n++) {
			int tag = u1(data, i);
			switch (tag) {
				case 1: {
					int len = u2(data, i + 1);
					char[] chars = new char[len];
					for (int k = 0; k < len; k++) {
						chars[k] = (char) u1(data, i + 3 + k);
					}
					utf8[n] = new String(chars);
					i += 3 + len;
					break;
				}
				case 3:
				case 4:
				case 17:
					i += 5;
					break;
				case 5:
				case 6:
					i += 9;
					n++;
					break;
				case 7:
				case 8:
				case 16:
				case 19:
				case 20:
					i += 3;
					break;
				case 9:
				case 10:
				case 11:
				case 18:
					refNat[n] = u2(data, i + 3);
					i += 5;
					break;
				case 12:
					natDesc[n] = u2(data, i + 3);
					i += 5;
					break;
				case 15:
					i += 4;
					break;
				default:
					return data;
			}
		}

		int interfaces = u2(data, i + 6);
		i += 8 + 2 * interfaces;

		int fields = u2(data, i);
		i += 2;
		for (int k = 0; k < fields; k++) {
			i = skipAttrs(data, i + 6);
		}

		byte[] out = null;
		int methods = u2(data, i);
		i += 2;
		for (int m = 0; m < methods; m++) {
			int attrCount = u2(data, i + 6);
			i += 8;
			for (int a = 0; a < attrCount; a++) {
				int nameIdx = u2(data, i);
				int attrLen = s4(data, i + 2);
				int off = i + 6;
				if ("Code".equals(utf8[nameIdx])) {
					int declStack = u2(data, off);
					int declLocals = u2(data, off + 2);
					int codeLen = s4(data, off + 4);
					byte[] code = Arrays.copyOfRange(data, off + 8, off + 8 + codeLen);
					int excPos = off + 8 + codeLen;
					int excCount = u2(data, excPos);
					int[] handlers = new int[excCount];
					for (int k = 0; k < excCount; k++) {
						handlers[k] = u2(data, excPos + 2 + 8 * k + 4);
					}
					int[] res;
					try {
						res = analyze(code, handlers, utf8, refNat, natDesc);
					} catch (RuntimeException e) {
						res = null;
					}
					if (res != null && (res[0] > declStack || res[1] > declLocals)) {
						if (out == null) {
							out = data.clone();
						}
						put2(out, off, Math.max(res[0], declStack));
						put2(out, off + 2, Math.max(res[1], declLocals));
					}
				}
				i = off + attrLen;
			}
		}
		return out != null ? out : data;
	}

	private static int localSize(int op) {
		switch (op) {
			case 21: case 23: case 25:
			case 54: case 56: case 58:
				return 1;
			case 22: case 24:
			case 55: case 57:
				return 2;
			default:
				return 0;
		}
	}

	private static int insnLen(byte[] code, int pc) {
		int op = u1(code, pc);
		if (op == 16 || op == 18 || op == 188 || op == 169
				|| (op >= 21 && op <= 25) || (op >= 54 && op <= 58)) {
			return 2;
		}
		if (op == 17 || op == 19 || op == 20 || op == 132 || op == 187 || op == 189
				|| op == 192 || op == 193 || op == 198 || op == 199
				|| (op >= 153 && op <= 168) || (op >= 178 && op <= 184)) {
			return 3;
		}
		if (op == 185 || op == 186 || op == 200 || op == 201) {
			return 5;
		}
		if (op == 197) {
			return 4;
		}
		if (op == 196) {
			return u1(code, pc + 1) == 132 ? 6 : 4;
		}
		if (op == 170) {
			int p = (pc + 4) & ~3;
			int lo = s4(code, p + 4);
			int hi = s4(code, p + 8);
			return p + 12 + 4 * (hi - lo + 1) - pc;
		}
		if (op == 171) {
			int p = (pc + 4) & ~3;
			int n = s4(code, p + 4);
			return p + 8 + 8 * n - pc;
		}
		return 1;
	}

	private static int fieldSlots(String desc) {
		char c = desc.charAt(0);
		return (c == 'J' || c == 'D') ? 2 : 1;
	}

	/** @return {argSlots, returnSlots} */
	private static int[] methodSlots(String desc) {
		int i = 1;
		int args = 0;
		while (desc.charAt(i) != ')') {
			char c = desc.charAt(i);
			if (c == 'J' || c == 'D') {
				args += 2;
				i++;
			} else if (c == 'L') {
				args++;
				i = desc.indexOf(';', i) + 1;
			} else if (c == '[') {
				while (desc.charAt(i) == '[') {
					i++;
				}
				if (desc.charAt(i) == 'L') {
					i = desc.indexOf(';', i) + 1;
				} else {
					i++;
				}
				args++;
			} else {
				args++;
				i++;
			}
		}
		char r = desc.charAt(i + 1);
		int ret = r == 'V' ? 0 : ((r == 'J' || r == 'D') ? 2 : 1);
		return new int[]{args, ret};
	}

	private static String refDesc(int idx, String[] utf8, int[] refNat, int[] natDesc) {
		return utf8[natDesc[refNat[idx]]];
	}

	/** @return {maxStack, maxLocals} needed by the code or null if it can't be analysed. */
	private static int[] analyze(byte[] code, int[] handlers, String[] utf8, int[] refNat, int[] natDesc) {
		int n = code.length;
		int[] depth = new int[n];
		Arrays.fill(depth, -1);
		ArrayList<int[]> work = new ArrayList<int[]>();
		work.add(new int[]{0, 0});
		for (int h : handlers) {
			work.add(new int[]{h, 1});
		}
		int maxStack = 0;
		int maxLocals = 0;
		int steps = 0;
		while (!work.isEmpty()) {
			int[] w = work.remove(work.size() - 1);
			int pc = w[0];
			int d = w[1];
			if (pc < 0 || pc >= n || d > 65535) {
				return null;
			}
			if (depth[pc] >= d) {
				continue;
			}
			depth[pc] = d;
			if (++steps > 2000000) {
				return null;
			}
			int op = u1(code, pc);
			int len = insnLen(code, pc);
			int next = pc + len;

			// local variables
			int ls = localSize(op);
			if (ls > 0) {
				maxLocals = Math.max(maxLocals, u1(code, pc + 1) + ls);
			} else if ((op >= 26 && op <= 45) || (op >= 59 && op <= 78)) {
				int base = op < 59 ? 26 : 59;
				int kind = (op - base) / 4;
				int size = (kind == 1 || kind == 3) ? 2 : 1;
				maxLocals = Math.max(maxLocals, (op - base) % 4 + size);
			} else if (op == 132 || op == 169) {
				maxLocals = Math.max(maxLocals, u1(code, pc + 1) + 1);
			} else if (op == 196) {
				int op2 = u1(code, pc + 1);
				int idx = u2(code, pc + 2);
				int sz = localSize(op2);
				maxLocals = Math.max(maxLocals, idx + (sz > 0 ? sz : 1));
			}

			// stack effect
			int pop;
			int push;
			if (op == 196) {
				int[] e = TABLE[u1(code, pc + 1)];
				if (e == null) {
					return null;
				}
				pop = e[0];
				push = e[1];
			} else if (op >= 178 && op <= 181) {
				int sz = fieldSlots(refDesc(u2(code, pc + 1), utf8, refNat, natDesc));
				if (op == 178) {
					pop = 0;
					push = sz;
				} else if (op == 179) {
					pop = sz;
					push = 0;
				} else if (op == 180) {
					pop = 1;
					push = sz;
				} else {
					pop = 1 + sz;
					push = 0;
				}
			} else if (op >= 182 && op <= 186) {
				int[] ms = methodSlots(refDesc(u2(code, pc + 1), utf8, refNat, natDesc));
				pop = ms[0] + ((op == 184 || op == 186) ? 0 : 1);
				push = ms[1];
			} else if (op == 197) {
				pop = u1(code, pc + 3);
				push = 1;
			} else {
				int[] e = TABLE[op];
				if (e == null) {
					return null;
				}
				pop = e[0];
				push = e[1];
			}
			int pre = d;
			int post = pre - pop + push;
			if (post < 0) {
				return null;
			}
			maxStack = Math.max(maxStack, Math.max(pre, post));

			// successors
			if ((op >= 153 && op <= 166) || op == 198 || op == 199) {
				work.add(new int[]{next, post});
				work.add(new int[]{pc + s2(code, pc + 1), post});
			} else if (op == 167) {
				work.add(new int[]{pc + s2(code, pc + 1), post});
			} else if (op == 200) {
				work.add(new int[]{pc + s4(code, pc + 1), post});
			} else if (op == 168) {
				work.add(new int[]{pc + s2(code, pc + 1), post});
				work.add(new int[]{next, pre});
			} else if (op == 201) {
				work.add(new int[]{pc + s4(code, pc + 1), post});
				work.add(new int[]{next, pre});
			} else if (op == 170) {
				int p = (pc + 4) & ~3;
				int dflt = s4(code, p);
				int lo = s4(code, p + 4);
				int hi = s4(code, p + 8);
				work.add(new int[]{pc + dflt, post});
				for (int k = 0; k <= hi - lo; k++) {
					work.add(new int[]{pc + s4(code, p + 12 + 4 * k), post});
				}
			} else if (op == 171) {
				int p = (pc + 4) & ~3;
				int dflt = s4(code, p);
				int pairs = s4(code, p + 4);
				work.add(new int[]{pc + dflt, post});
				for (int k = 0; k < pairs; k++) {
					work.add(new int[]{pc + s4(code, p + 12 + 8 * k), post});
				}
			} else if ((op >= 172 && op <= 177) || op == 191 || op == 169) {
				// no successors
			} else {
				work.add(new int[]{next, post});
			}
		}
		return new int[]{maxStack, maxLocals};
	}
}
