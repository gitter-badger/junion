/*
 * Copyright (c) 2018, Juraj Papp
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the copyright holder nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package theleo.jstruct.hidden;

import java.lang.reflect.Field;
import sun.misc.Unsafe;
import theleo.jstruct.NullPointerDereference;
import theleo.jstruct.StackOutOfMemory;
import theleo.jstruct.WildPointerException;

/**
 *
 * @author Juraj Papp
 */
public class Mem0 {
	public static long STACK_INIT_SIZE = 1024;
	public static long STACK_MAX_SIZE = 262144;
	public static int STACK_INIT_OBJ_SIZE = 32;
	public static int STACK_MAX_OBJ_SIZE = 262144;
	
	public static final Unsafe u;
	private static final long BLOCKER_LOCK_OFFSET;

	static {
		u = getUnsafe();
		Field f = null;
		long offset = 0;
		try {
			f = Thread.class.getDeclaredField("blockerLock");
			f.setAccessible(true);
			offset = Mem0.u.objectFieldOffset(f);
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalArgumentException("Could not initialize");
		}		
		BLOCKER_LOCK_OFFSET = offset;
	}
	static final Object allocOwner = new Object();
	static final Object ownersDataLock = new Object();
	static Object[][] ownersData = new Object[128][];
	static int lastFreeOwner = 0;
	
	public static <T> T getObject(long addr, int offset) {
		return getObject0(u.getInt(addr), u.getInt(addr+4) + offset);
	}
	public static <T> T getObject0(int owner, int i) {
		Object[][] a = ownersData;
		if(owner >= a.length) {
			synchronized(ownersDataLock) {
				Object[][] c = ownersData;
				if(owner >= c.length)
					throw new WildPointerException();
				return (T)c[owner][i];
			}
		}
		return (T)a[owner][i];
	}
	public static void putObject(long addr, int offset, Object o) {
		putObject0(u.getInt(addr), u.getInt(addr+4)+offset, o);
	}
	public static void putObject0(int owner, int i, Object o) {
		Object[][] a = ownersData;
		if(owner >= a.length) {
			synchronized(ownersDataLock) {
				Object[][] c = ownersData;
				if(owner >= c.length)
					throw new WildPointerException();
				c[owner][i]=o;
			}
		}
		a[owner][i]=o;
	}
	
	public static int allocObjectArray(Object[] arr) {
		synchronized(allocOwner) {
			int found = -1;
			Object[] odata = ownersData;
			int l = lastFreeOwner;
			int size = odata.length;
			for(int i = l; i < size; i++)
				if(odata[i] == null) {
					found = i;
					break;
				}
			if(found == -1) {
				for(int i = 0; i < l; i++)
					if(odata[i] == null) {
						found = i;
						break;
					}
			}
			if(found == -1) {
				Object[][] grow = new Object[size<<1][];
				System.arraycopy(odata, 0, grow, 0, size);
				odata = grow;
				size = grow.length;
				synchronized(ownersDataLock) {
					ownersData = grow;
				}
				found = size;
			}
			odata[found] = arr;
			lastFreeOwner = found+1;
			return found;
		}
	}
	
	public static long allocHybOnStack(long addr, int posObj, int hybridIndex, int... ownerOffet) {
//		if(zero) u.setMemory(base, allocSize, (byte)0);
		
		for(int i = 0; i < ownerOffet.length; i += 2) {
			int off = ownerOffet[i];
			int count = ownerOffet[i+1];

			u.putInt(addr+off, hybridIndex);
			u.putInt(addr+off+4, posObj);
			posObj += count;
		}
		
		return addr;
	}
		
	public static AutoHybrid allocHybrid(long length, long structSize, boolean zero, int glObjCount, int... ownerOffet) {
		long allocSize = length*structSize;
		long base = alloc(allocSize);
		if(zero) u.setMemory(base, allocSize, (byte)0);
		AutoHybrid auto = new AutoHybrid(base, length, structSize, glObjCount);
		
		if(length == 0) return auto;
		int found = allocObjectArray(auto.hybridData);
		auto.hybridIndex = found;
		int index = 0;
		for(long l = 0; l < length; l++) {
			for(int i = 0; i < ownerOffet.length; i += 2) {
				int off = ownerOffet[i];
				int count = ownerOffet[i+1];
				
				u.putInt(base+off, found);
				u.putInt(base+off+4, index);
				index += count;
			}
			base += structSize;
		}
		return auto;
	}
	public static AutoArray alloc(long length, long structSize, boolean zero) {
		long size = length*structSize;
		long base = alloc(size);
		if(zero) u.setMemory(base, size, (byte)0);
		return new AutoArray(base, length, structSize);
	}
	public static long alloc(long size) {
		return u.allocateMemory(size);
	}
	public static void freeHybrid(long ptr, int hybridIndex) {
		u.freeMemory(ptr);
		ownersData[hybridIndex] = null;
	}
	public static void freeObjectArray(int hybridIndex) {
		ownersData[hybridIndex] = null;
	}
	public static void free(long ptr) {
		u.freeMemory(ptr);
	}
	
	public static Stack stack() {
		Thread t = Thread.currentThread();
		Object o = Mem0.u.getObject(t, BLOCKER_LOCK_OFFSET);
		if(o instanceof Stack) {
			return (Stack)o;
		}
		else {
			Stack s = new Stack(STACK_INIT_SIZE, STACK_INIT_OBJ_SIZE);
			synchronized(o) {
				Mem0.u.putObject(t, BLOCKER_LOCK_OFFSET, s);
			}
			return s;
		}
	}
	
	public static void stackGrowArray(Stack s) {
		long size = s.hybridData.length<<1;
		if(size > Mem0.STACK_MAX_OBJ_SIZE) throw new StackOutOfMemory();
		Object[] arr = new Object[(int)size];
		System.arraycopy(s.hybridData, 0, arr, 0, s.hybridData.length);
		s.hybridData = arr;
		//Synchronization for visibility update should not be necessary
		//since stack is used by single thread.
		ownersData[s.hybridIndex] = arr;
	}
	
	/**
	 * Allows to reinterpret cast struct type.
	 *  
	 * @param o - struct type
	 * @return 
	 */
	public static <T> T as(Object o) {
		return (T)o;
	}
	
	/**
	 * Allocates struct on thread's stack. As with any stack allocation, 
	 * stack allocated structs are valid until the method returns.
	 * 
	 * @param classLiteral class literal
	 * @return stack allocated struct
	 */
	public static <T> T stackRaw(Class<T> classLiteral) { throw new CompileException(); }
	
	
	
	public static boolean isNull(Object o) {
		return o == null;
	}
	public static boolean isNull(long o) {
		o = u.getLong(o);
		return o == 0;
	}
	public static long ref(long l) {
		if(l == 0) throw new NullPointerDereference();
		return l;
	}
	public static long ptr(long l) {
		l = u.getLong(l);
		if(l == 0) throw new NullPointerDereference();
		return l;
	}
	
	public static long copyMemory(long from, long to, long bytes) {
		u.copyMemory(from, to, bytes);
		return to;
	}
	public static long copyHybrid(long from, long to, long bytes, int... ownerOffet) {		
		int oFrom = u.getInt(from+ownerOffet[0]);
		int oFromI = u.getInt(from+ownerOffet[0]+4);
		int oTo = u.getInt(to+ownerOffet[0]);
		int oToI = u.getInt(to+ownerOffet[0]+4);
		
		int k = 0;
		int add = 0;
		
		for(int i = 0; i < bytes; i+=8) {
			if(k < ownerOffet.length && ownerOffet[k] == i) {
				int count = ownerOffet[k+1];
				for(int j = 0; j < count; j++) {
					putObject0(oTo, oToI+add,
							getObject0(oFrom, oFromI+add));
					add++;
				}
				k+=2;
			}
			else u.copyMemory(from+i, to+i, i+8 > bytes? bytes-i: 8);
		}
		return to;
	}
	
	public static boolean getBoolean(long addr) {
//		if(addr == 0) throw new NullPointerException();
		return u.getByte(addr)!=0;
	}
	public static boolean putBoolean(long addr, boolean f) {
//		if(addr == 0) throw new NullPointerException();
		u.putByte(addr, f?(byte)1:(byte)0);
		return f;
	}
	
	
//	public static boolean getBoolean(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getByte(addr)!=0;
//	}
//	public static byte getByte(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getByte(addr);
//	}
//	public static short getShort(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getShort(addr);
//	}
//	public static char getChar(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getChar(addr);
//	}
//	public static float getFloat(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getFloat(addr);
//	}
//	public static double getDouble(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getDouble(addr);
//	}
//	public static int getInt(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getInt(addr);
//	}
//	public static long getLong(long addr) {
//		if(addr == 0) throw new NullPointerException();
//		return u.getLong(addr);
//	}
//	
//	public static boolean putBoolean(long addr, boolean f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putByte(addr, (byte)1);
//		return f;
//	}
//	public static byte putByte(long addr, byte f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putByte(addr, f);
//		return f;
//	}
//	public static short putShort(long addr, short f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putShort(addr, f);
//		return f;
//	}
//	public static char putChar(long addr, char f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putChar(addr, f);
//		return f;
//	}
//	public static float putFloat(long addr, float f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putFloat(addr, f);
//		return f;
//	}
//	public static double putDouble(long addr, double f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putDouble(addr, f);
//		return f;
//	}
//	public static int putInt(long addr, int f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putInt(addr, f);
//		return f;
//	}
//	public static long putLong(long addr, long f) {
////		if(addr == 0) throw new NullPointerException();
//		u.putLong(addr, f);
//		return f;
//	}
	
	private static Unsafe getUnsafe() {
		try {
			Field singleoneInstanceField = Unsafe.class.getDeclaredField("theUnsafe");
			singleoneInstanceField.setAccessible(true);
			return (Unsafe) singleoneInstanceField.get(null);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}
}
