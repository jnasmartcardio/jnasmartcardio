/*
 * To the extent possible under law, contributors have waived all
 * copyright and related or neighboring rights to work.
 */
package jnasmartcardio;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sun.jna.FunctionMapper;
import com.sun.jna.IntegerType;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.NativeMappedConverter;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;


class Winscard {
	public static class Scope extends Structure implements Structure.ByValue {
		public int scope;
		@Override protected List<String> getFieldOrder() {
			return Arrays.asList("scope");
		}
	}
	public static int DWORD_SIZE = Platform.isWindows() || Platform.isMac() ? 4 : NativeLong.SIZE;
	// typedef LONG SCARDCONTEXT;
	public static class SCardContext extends IntegerType {
		private static final long serialVersionUID = 1L;
		public static final int SIZE = Platform.isWindows() ? Pointer.SIZE : DWORD_SIZE;
		/** no-arg constructor needed for {@link NativeMappedConverter#defaultValue()}*/
		public SCardContext() {this(0l);}
		public SCardContext(long value) {
			super(SIZE, value);
		}
	}
	public static class SCardContextByReference extends ByReference {
		public SCardContextByReference() {super(SCardContext.SIZE);}
		public SCardContext getValue() {
			long v = SCardContext.SIZE == 4 ? getPointer().getInt(0) : getPointer().getLong(0);
			return new SCardContext(v);
		}
		public void setValue(SCardContext context) {
			if (SCardContext.SIZE == 4) {
				getPointer().setInt(0, context.intValue());
			} else {
				getPointer().setLong(0, context.longValue());
			}
		}
	}
	public static class SCardHandle extends Structure implements Structure.ByValue {
		public NativeLong scard;
		public SCardHandle(NativeLong nativeLong) {this.scard = nativeLong;}
		@Override protected List<String> getFieldOrder() {
			return Arrays.asList("scard");
		}
	}
	public static class SCardHandleByReference extends ByReference {
		public SCardHandleByReference() {super(NativeLong.SIZE);}
		public SCardHandle getValue() {
			return new SCardHandle(getPointer().getNativeLong(0));
		}
		public void setValue(SCardHandle context) {
			getPointer().setNativeLong(0, context.scard);
		}
	}
	// typedef struct _SCARD_IO_REQUEST {
	//   uint32_t dwProtocol;    /**< Protocol identifier */
	//   uint32_t cbPciLength;   /**< Protocol Control Inf Length */
	// }
	public static class ScardIoRequest extends PointerType {
		public ScardIoRequest() {super();}
		public ScardIoRequest(Pointer p) {super(p);}
	}

	/**
	 * Interface to wrap the particular SCARD_READERSTATE declaration on the
	 * platform. Unfortunately, each platform has a different type:
	 * 
	 * <ul>
	 * <li>Windows has extra padding after rgbAtr, so that the structure is
	 * aligned at word boundaries even when it is in an array
	 * SCARD_READERSTATE[]
	 * <li>OSX has no extra padding around rgbAtr, so that array elements are
	 * not word-aligned.
	 * <li>Linux pcsclite has no extra padding around rgbAtr. In addition, DWORD
	 * is typedef'd to long instead of int.
	 * </ul>
	 */
	public interface SCardReaderState {
		public void setReaderName(String szReader);
		public String getReaderName();
		public void setCurrentState(int dwCurrentState);
		public int getCurrentState();
		public void setEventState(int dwEventState);
		public int getEventState();
		public void toArray(SCardReaderState[] array);
		public int getAtrLength();
		public void setAtrLength(int cbAtr);
		public byte[] getAtrArray();
	}
	/**
	 * On Windows, SCardReaderState is explicitly aligned to word boundaries.
	 * 
	 * <p>
	 * sizeof(SCARD_READERSTATE_A):<br>
	 * windows x86: 4+4+4+4+4+36 = 56<br>
	 * windows x64: 8+8+4+4+4+36 = 64<br>
	 * structure alignment: not sure (but it doesn't matter)
	 */
	public static class WinscardSCardReaderState extends Structure implements SCardReaderState {
		// const char *szReader;
		public String szReader;
		// void *pvUserData;
		public Pointer pvUserData;
		// uint32_t dwCurrentState;
		public int dwCurrentState;
		// uint32_t dwEventState;
		public int dwEventState;
		// uint32_t cbAtr;
		public int cbAtr;
		public byte[] rgbAtr = new byte[WinscardConstants.MAX_ATR_SIZE];
		protected WinscardSCardReaderState(int align){super((Pointer)null,align);}
		public WinscardSCardReaderState(){super(); assert this.size() == Pointer.SIZE * 2 + 12 + 36;}
		public WinscardSCardReaderState(String szReader) {this(); this.szReader = szReader;}
		@Override protected List<String> getFieldOrder() {
			return Arrays.asList("szReader", "pvUserData", "dwCurrentState", "dwEventState", "cbAtr", "rgbAtr");
		}
		@Override public void setReaderName(String szReader) { this.szReader = szReader; }
		@Override public String getReaderName() {return this.szReader;}
		@Override public void setCurrentState(int dwCurrentState) {this.dwCurrentState = (int) dwCurrentState;}
		@Override public int getCurrentState() {return this.dwCurrentState;}
		@Override public void setEventState(int dwEventState) {this.dwEventState = (int) dwEventState;}
		@Override public int getEventState() {return this.dwEventState;}
		@Override public void toArray(SCardReaderState[] array) {super.toArray((Structure[])array);}
		@Override public int getAtrLength() {return cbAtr;}
		@Override public void setAtrLength(int cbAtr) {this.cbAtr = cbAtr;}
		@Override public byte[] getAtrArray() {return this.rgbAtr;}
	}
	
	/**
	 * On OS X, the fields of SCardReaderState are the same size as Windows, but
	 * it is not aligned to word boundaries because pcsclite.h contains
	 * "#pragma pack(1)", and unlike Windows it is not explicitly padded.
	 *
	 * <p>
	 * sizeof(SCARD_READERSTATE_A):<br>
	 * osx x86: 4+4+4+4+4+33 = 53<br>
	 * osx x64: 8+8+4+4+4+33 = 61<br>
	 * structure alignment: packed
	 * 
	 * @see http://gcc.gnu.org/onlinedocs/gcc/Structure_002dPacking-Pragmas.html
	 */
	public static class OSXSCardReaderState extends WinscardSCardReaderState {
		public OSXSCardReaderState(){super(ALIGN_NONE); assert this.size() == Pointer.SIZE * 2 + 12 + 33;}
		public OSXSCardReaderState(String szReader) {this(); this.szReader = szReader;}
		@Override protected List<String> getFieldOrder() {
			return Arrays.asList("szReader", "pvUserData", "dwCurrentState", "dwEventState", "cbAtr", "rgbAtr");
		}
	}
	
	/**
	 * On Linux, SCardReaderState is aligned by default (there is no #pragma
	 * pack(1)), but the fields are long
	 * 
	 * <p>
	 * sizeof(SCARD_READERSTATE_A):<br>
	 * linux x86: 4+4+4+4+4+33 = 53<br>
	 * linux x64: 8+8+8+8+8+33 = 73<br>
	 * structure alignment: default
	 */
	public static class LinuxSCardReaderState extends Structure implements SCardReaderState {
		// const char *szReader;
		public String szReader;
		// void *pvUserData;
		public Pointer pvUserData;
		// DWORD dwCurrentState;
		public NativeLong dwCurrentState;
		// DWORD dwEventState;
		public NativeLong dwEventState;
		// DWORD cbAtr;
		public NativeLong cbAtr;
		public byte[] rgbAtr = new byte[WinscardConstants.MAX_ATR_SIZE];
		public LinuxSCardReaderState(){super((Pointer)null,ALIGN_DEFAULT); assert this.size() == Pointer.SIZE * 2 + NativeLong.SIZE * 3 + 33;}
		public LinuxSCardReaderState(String szReader) {this(); this.szReader = szReader;}
		@Override protected List<String> getFieldOrder() {
			return Arrays.asList("szReader", "pvUserData", "dwCurrentState", "dwEventState", "cbAtr", "rgbAtr");
		}
		@Override public void setReaderName(String szReader) { this.szReader = szReader; }
		@Override public String getReaderName() {return this.szReader;}
		@Override public void setCurrentState(int dwCurrentState) {this.dwCurrentState = new NativeLong(0xffffffffl & dwCurrentState);}
		@Override public int getCurrentState() {return this.dwCurrentState == null ? 0 : (int)this.dwCurrentState.longValue();}
		@Override public void setEventState(int dwEventState) {this.dwEventState = new NativeLong(0xffffffffl & dwEventState);}
		@Override public int getEventState() {return this.dwEventState == null ? 0 : (int)this.dwEventState.longValue();}
		@Override public void toArray(SCardReaderState[] array) {super.toArray((Structure[])array);}
		@Override public int getAtrLength() {return cbAtr == null ? 0 : cbAtr.intValue();}
		@Override public void setAtrLength(int cbAtr) {this.cbAtr = new NativeLong(cbAtr);}
		@Override public byte[] getAtrArray() {return this.rgbAtr;}
	}
	public static final String WINDOWS_PATH = "WinSCard.dll";
	public static final String MAC_PATH = "/System/Library/Frameworks/PCSC.framework/PCSC";
	public static final String PCSC_PATH = "libpcsclite.so.1";

	/**
	 * The winscard API, used on Windows. OS X also uses the same declarations;
	 * they forked PCSC but made sure the declarations are the same as Windows.
	 */
	public interface WinscardLibrary extends Library {
		// LONG SCardEstablishContext (DWORD dwScope, LPCVOID pvReserved1, LPCVOID pvReserved2, LPSCARDCONTEXT phContext)
		NativeLong SCardEstablishContext (int dwScope, Pointer pvReserved1, Pointer pvReserved2, SCardContextByReference phContext);
		// LONG 	SCardReleaseContext (SCARDCONTEXT hContext)
		NativeLong SCardReleaseContext(SCardContext hContext);
		// LONG 	SCardConnect (SCARDCONTEXT hContext, LPCSTR szReader, DWORD dwShareMode, DWORD dwPreferredProtocols, LPSCARDHANDLE phCard, LPDWORD pdwActiveProtocol)
		NativeLong SCardConnect(SCardContext hContext, String szReader, int dwSharMode, int dwPreferredProtocols, SCardHandleByReference phCard, IntByReference pdwActiveProtocol);
		// LONG 	SCardReconnect (SCARDHANDLE hCard, DWORD dwShareMode, DWORD dwPreferredProtocols, DWORD dwInitialization, LPDWORD pdwActiveProtocol)
		NativeLong SCardReconnect(SCardHandle hCard, int dwShareMode, int dwPreferredProtocols, int dwInitialization, IntByReference pdwActiveProtocol);
		// LONG 	SCardDisconnect (SCARDHANDLE hCard, DWORD dwDisposition)
		NativeLong SCardDisconnect (SCardHandle hCard, int dwDisposition);
		// LONG 	SCardBeginTransaction (SCARDHANDLE hCard)
		NativeLong SCardBeginTransaction(SCardHandle hCard);
		// LONG 	SCardBeginTransaction (SCARDHANDLE hCard)
		NativeLong SCardEndTransaction(SCardHandle hCard, int dwDisposition);
		// LONG 	SCardStatus (SCARDHANDLE hCard, LPSTR mszReaderName, LPDWORD pcchReaderLen, LPDWORD pdwState, LPDWORD pdwProtocol, LPBYTE pbAtr, LPDWORD pcbAtrLen)
		NativeLong SCardStatus(SCardHandle hCard, ByteBuffer mszReaderName, IntByReference pcchReaderLen, IntByReference pdwState, IntByReference pdwProtocol, ByteBuffer pbAtr, IntByReference pcbAtrLen);
		// LONG 	SCardGetStatusChange (SCARDCONTEXT hContext, DWORD dwTimeout, SCARD_READERSTATE *rgReaderStates, DWORD cReaders)
		NativeLong SCardGetStatusChange(SCardContext hContext, int dwTimeout, SCardReaderState[] rgReaderStates, int cReaders);
		// LONG 	SCardControl (SCARDHANDLE hCard, DWORD dwControlCode, LPCVOID pbSendBuffer, DWORD cbSendLength, LPVOID pbRecvBuffer, DWORD cbRecvLength, LPDWORD lpBytesReturned)
		NativeLong SCardControl(SCardHandle hCard, int dwControlCode, ByteBuffer pbSendBuffer, int cbSendLength, ByteBuffer pbRecvBuffer, int cbRecvLength, IntByReference lpBytesReturned);
		// LONG 	SCardGetAttrib (SCARDHANDLE hCard, DWORD dwAttrId, LPBYTE pbAttr, LPDWORD pcbAttrLen)
		NativeLong SCardGetAttrib(SCardHandle hCard, int dwAttrId, ByteBuffer pbAttr, IntByReference pcbAttrLen);
		// LONG 	SCardSetAttrib (SCARDHANDLE hCard, DWORD dwAttrId, LPCBYTE pbAttr, DWORD cbAttrLen)
		NativeLong SCardSetAttrib(SCardHandle hCard, int dwAttrId, ByteBuffer pbAttr, int cbAttrLen);
		// LONG 	SCardTransmit (SCARDHANDLE hCard, const SCARD_IO_REQUEST *pioSendPci, LPCBYTE pbSendBuffer, DWORD cbSendLength, SCARD_IO_REQUEST *pioRecvPci, LPBYTE pbRecvBuffer, LPDWORD pcbRecvLength)
		NativeLong SCardTransmit(SCardHandle hCard, ScardIoRequest pioSendPci, ByteBuffer pbSendBuffer, int cbSendLength, ScardIoRequest pioRecvPci, ByteBuffer pbRecvBuffer, IntByReference pcbRecvLength);
		// LONG 	SCardListReaders (SCARDCONTEXT hContext, LPCSTR mszGroups, LPSTR mszReaders, LPDWORD pcchReaders)
		NativeLong SCardListReaders(SCardContext hContext, ByteBuffer mszGroups, ByteBuffer mszReaders, IntByReference pcchReaders);
		// LONG 	SCardFreeMemory (SCARDCONTEXT hContext, LPCVOID pvMem)
		NativeLong SCardFreeMemory(SCardContext hContext, Pointer pvMem);
		// LONG 	SCardListReaderGroups (SCARDCONTEXT hContext, LPSTR mszGroups, LPDWORD pcchGroups)
		NativeLong SCardListReaderGroups(SCardContext hContext, ByteBuffer mszGroups, IntByReference pcchGroups);
		// LONG 	SCardCancel (SCARDCONTEXT hContext)
		NativeLong SCardCancel(SCardContext hContext);
		// LONG 	SCardIsValidContext (SCARDCONTEXT hContext)
		NativeLong SCardIsValidContext (SCardContext hContext);
	}

	/**
	 * Unfortunately, the pcsc-lite library typedef'd DWORD as unsigned long, so
	 * on 64-bit platforms we need a different set of signatures. On x86-64,
	 * most functions that take DWORD will still work because the first 6 args
	 * are in the registers. But functions that take DWORD* will misbehave if
	 * you use the Winscard signatures.
	 */
	public interface PcscLiteLibrary extends Library {
		// LONG SCardEstablishContext (DWORD dwScope, LPCVOID pvReserved1, LPCVOID pvReserved2, LPSCARDCONTEXT phContext)
		NativeLong SCardEstablishContext (NativeLong dwScope, Pointer pvReserved1, Pointer pvReserved2, SCardContextByReference phContext);
		// LONG 	SCardReleaseContext (SCARDCONTEXT hContext)
		NativeLong SCardReleaseContext(SCardContext hContext);
		// LONG 	SCardConnect (SCARDCONTEXT hContext, LPCSTR szReader, DWORD dwShareMode, DWORD dwPreferredProtocols, LPSCARDHANDLE phCard, LPDWORD pdwActiveProtocol)
		NativeLong SCardConnect(SCardContext hContext, String szReader, NativeLong dwSharMode, NativeLong dwPreferredProtocols, SCardHandleByReference phCard, NativeLongByReference pdwActiveProtocol);
		// LONG 	SCardReconnect (SCARDHANDLE hCard, DWORD dwShareMode, DWORD dwPreferredProtocols, DWORD dwInitialization, LPDWORD pdwActiveProtocol)
		NativeLong SCardReconnect(SCardHandle hCard, NativeLong dwShareMode, NativeLong dwPreferredProtocols, NativeLong dwInitialization, NativeLongByReference pdwActiveProtocol);
		// LONG 	SCardDisconnect (SCARDHANDLE hCard, DWORD dwDisposition)
		NativeLong SCardDisconnect (SCardHandle hCard, NativeLong dwDisposition);
		// LONG 	SCardBeginTransaction (SCARDHANDLE hCard)
		NativeLong SCardBeginTransaction(SCardHandle hCard);
		// LONG 	SCardEndTransaction (SCARDHANDLE hCard, DWORD dwDisposition)
		NativeLong SCardEndTransaction(SCardHandle hCard, NativeLong dwDisposition);
		// LONG 	SCardStatus (SCARDHANDLE hCard, LPSTR mszReaderName, LPDWORD pcchReaderLen, LPDWORD pdwState, LPDWORD pdwProtocol, LPBYTE pbAtr, LPDWORD pcbAtrLen)
		NativeLong SCardStatus(SCardHandle hCard, ByteBuffer mszReaderName, NativeLongByReference pcchReaderLen, NativeLongByReference pdwState, NativeLongByReference pdwProtocol, ByteBuffer pbAtr, NativeLongByReference pcbAtrLen);
		// LONG 	SCardGetStatusChange (SCARDCONTEXT hContext, DWORD dwTimeout, SCARD_READERSTATE *rgReaderStates, DWORD cReaders)
		NativeLong SCardGetStatusChange(SCardContext hContext, NativeLong dwTimeout, SCardReaderState[] rgReaderStates, NativeLong cReaders);
		// LONG 	SCardControl (SCARDHANDLE hCard, DWORD dwControlCode, LPCVOID pbSendBuffer, DWORD cbSendLength, LPVOID pbRecvBuffer, DWORD cbRecvLength, LPDWORD lpBytesReturned)
		NativeLong SCardControl(SCardHandle hCard, NativeLong dwControlCode, ByteBuffer pbSendBuffer, NativeLong cbSendLength, ByteBuffer pbRecvBuffer, NativeLong cbRecvLength, NativeLongByReference lpBytesReturned);
		// LONG 	SCardGetAttrib (SCARDHANDLE hCard, DWORD dwAttrId, LPBYTE pbAttr, LPDWORD pcbAttrLen)
		NativeLong SCardGetAttrib(SCardHandle hCard, NativeLong dwAttrId, ByteBuffer pbAttr, NativeLongByReference pcbAttrLen);
		// LONG 	SCardSetAttrib (SCARDHANDLE hCard, DWORD dwAttrId, LPCBYTE pbAttr, DWORD cbAttrLen)
		NativeLong SCardSetAttrib(SCardHandle hCard, NativeLong dwAttrId, ByteBuffer pbAttr, NativeLong cbAttrLen);
		// LONG 	SCardTransmit (SCARDHANDLE hCard, const SCARD_IO_REQUEST *pioSendPci, LPCBYTE pbSendBuffer, DWORD cbSendLength, SCARD_IO_REQUEST *pioRecvPci, LPBYTE pbRecvBuffer, LPDWORD pcbRecvLength)
		NativeLong SCardTransmit(SCardHandle hCard, ScardIoRequest pioSendPci, ByteBuffer pbSendBuffer, NativeLong cbSendLength, ScardIoRequest pioRecvPci, ByteBuffer pbRecvBuffer, NativeLongByReference pcbRecvLength);
		// LONG 	SCardListReaders (SCARDCONTEXT hContext, LPCSTR mszGroups, LPSTR mszReaders, LPDWORD pcchReaders)
		NativeLong SCardListReaders(SCardContext hContext, ByteBuffer mszGroups, ByteBuffer mszReaders, NativeLongByReference pcchReaders);
		// LONG 	SCardFreeMemory (SCARDCONTEXT hContext, LPCVOID pvMem)
		NativeLong SCardFreeMemory(SCardContext hContext, Pointer pvMem);
		// LONG 	SCardListReaderGroups (SCARDCONTEXT hContext, LPSTR mszGroups, LPDWORD pcchGroups)
		NativeLong SCardListReaderGroups(SCardContext hContext, ByteBuffer mszGroups, NativeLongByReference pcchGroups);
		// LONG 	SCardCancel (SCARDCONTEXT hContext)
		NativeLong SCardCancel(SCardContext hContext);
		// LONG 	SCardIsValidContext (SCARDCONTEXT hContext)
		NativeLong SCardIsValidContext (SCardContext hContext);
	}

	/**
	 * Adapter that allows you to use the Winscard interface to talk to
	 * pcsc-lite on Linux. Fortunately, all the DWORD/DWORD* arguments in the
	 * PCSC interface should never contain anything in the upper 32 bits.
	 */
	public static class PcscLiteAdapter implements WinscardLibrary {
		private final PcscLiteLibrary delegate;
		public PcscLiteAdapter(PcscLiteLibrary delegate) {
			this.delegate = delegate;
		}
		public NativeLong SCardEstablishContext(int dwScope, Pointer pvReserved1, Pointer pvReserved2, SCardContextByReference phContext) {
			return delegate.SCardEstablishContext(new NativeLong(dwScope), pvReserved1, pvReserved2, phContext);
		}
		public NativeLong SCardReleaseContext(SCardContext hContext) {
			return delegate.SCardReleaseContext(hContext);
		}
		public NativeLong SCardConnect(SCardContext hContext, String szReader, int dwSharMode, int dwPreferredProtocols, SCardHandleByReference phCard, IntByReference pdwActiveProtocol) {
			NativeLongByReference longActiveProtocol = pdwActiveProtocol == null ? null : new NativeLongByReference();
			NativeLong r = delegate.SCardConnect(hContext, szReader, new NativeLong(dwSharMode), new NativeLong(dwPreferredProtocols), phCard, longActiveProtocol);
			if (pdwActiveProtocol != null)
				pdwActiveProtocol.setValue(longActiveProtocol.getValue().intValue());
			return r;
		}
		public NativeLong SCardReconnect(SCardHandle hCard, int dwShareMode, int dwPreferredProtocols, int dwInitialization, IntByReference pdwActiveProtocol) {
			NativeLongByReference longActiveProtocol = pdwActiveProtocol == null ? null : new NativeLongByReference();
			NativeLong r = delegate.SCardReconnect(hCard, new NativeLong(dwShareMode), new NativeLong(dwPreferredProtocols), new NativeLong(dwInitialization), longActiveProtocol);
			if (pdwActiveProtocol != null)
				pdwActiveProtocol.setValue(longActiveProtocol.getValue().intValue());
			return r;
		}
		public NativeLong SCardDisconnect(SCardHandle hCard, int dwDisposition) {
			return delegate.SCardDisconnect(hCard, new NativeLong(dwDisposition));
		}
		public NativeLong SCardBeginTransaction(SCardHandle hCard) {
			return delegate.SCardBeginTransaction(hCard);
		}
		public NativeLong SCardEndTransaction(SCardHandle hCard, int dwDisposition) {
			return delegate.SCardEndTransaction(hCard, new NativeLong(dwDisposition));
		}
		public NativeLong SCardStatus(SCardHandle hCard, ByteBuffer mszReaderName, IntByReference pcchReaderLen, IntByReference pdwState, IntByReference pdwProtocol, ByteBuffer pbAtr, IntByReference pcbAtrLen) {
			NativeLongByReference longReaderLen = pcchReaderLen == null ? null : new NativeLongByReference(new NativeLong(pcchReaderLen.getValue()));  // nullable inout
			NativeLongByReference longState = pdwState == null ? null : new NativeLongByReference();  // nullable out
			NativeLongByReference longProtocol = pdwProtocol == null ? null : new NativeLongByReference();  // nullable out
			NativeLongByReference longAtrLen = pcbAtrLen == null ? null : new NativeLongByReference(new NativeLong(pcbAtrLen.getValue()));  // nullable inout
			NativeLong r = delegate.SCardStatus(hCard, mszReaderName, longReaderLen, longState, longProtocol, pbAtr, longAtrLen);
			if (pcchReaderLen != null)
				pcchReaderLen.setValue(longReaderLen.getValue().intValue());
			if (pdwState != null)
				pdwState.setValue(longState.getValue().intValue());
			if (pdwProtocol != null)
				pdwProtocol.setValue(longProtocol.getValue().intValue());
			if (pcbAtrLen != null)
				pcbAtrLen.setValue(longAtrLen.getValue().intValue());
			return r;
		}
		public NativeLong SCardGetStatusChange(SCardContext hContext, int dwTimeout, SCardReaderState[] rgReaderStates, int cReaders) {
			return delegate.SCardGetStatusChange(hContext, new NativeLong(dwTimeout), rgReaderStates, new NativeLong(cReaders));
		}
		public NativeLong SCardControl(SCardHandle hCard, int dwControlCode, ByteBuffer pbSendBuffer, int cbSendLength, ByteBuffer pbRecvBuffer, int cbRecvLength, IntByReference lpBytesReturned) {
			NativeLongByReference longBytesReturned = new NativeLongByReference();  // out
			NativeLong r = delegate.SCardControl(hCard, new NativeLong(dwControlCode), pbSendBuffer, new NativeLong(cbSendLength), pbRecvBuffer, new NativeLong(cbRecvLength), longBytesReturned);
			lpBytesReturned.setValue(longBytesReturned.getValue().intValue());
			return r;
		}
		public NativeLong SCardGetAttrib(SCardHandle hCard, int dwAttrId, ByteBuffer pbAttr, IntByReference pcbAttrLen) {
			NativeLongByReference longAttrLen = new NativeLongByReference(new NativeLong(pcbAttrLen.getValue()));  // inout
			NativeLong r = delegate.SCardGetAttrib(hCard, new NativeLong(dwAttrId), pbAttr, longAttrLen);
			pcbAttrLen.setValue(longAttrLen.getValue().intValue());
			return r;
		}
		public NativeLong SCardSetAttrib(SCardHandle hCard, int dwAttrId, ByteBuffer pbAttr, int cbAttrLen) {
			return delegate.SCardSetAttrib(hCard, new NativeLong(dwAttrId), pbAttr, new NativeLong(cbAttrLen));
		}
		public NativeLong SCardTransmit(SCardHandle hCard, ScardIoRequest pioSendPci, ByteBuffer pbSendBuffer, int cbSendLength, ScardIoRequest pioRecvPci, ByteBuffer pbRecvBuffer, IntByReference pcbRecvLength) {
			NativeLongByReference longRecvLength = new NativeLongByReference(new NativeLong(pcbRecvLength.getValue()));  // inout
			NativeLong r = delegate.SCardTransmit(hCard, pioSendPci, pbSendBuffer, new NativeLong(cbSendLength), pioRecvPci, pbRecvBuffer, longRecvLength);
			pcbRecvLength.setValue(longRecvLength.getValue().intValue());
			return r;
		}
		public NativeLong SCardListReaders(SCardContext hContext, ByteBuffer mszGroups, ByteBuffer mszReaders, IntByReference pcchReaders) {
			NativeLongByReference longNumReaders = new NativeLongByReference(new NativeLong(pcchReaders.getValue()));  // inout
			NativeLong r = delegate.SCardListReaders(hContext, mszGroups, mszReaders, longNumReaders);
			pcchReaders.setValue(longNumReaders.getValue().intValue());
			return r;
		}
		public NativeLong SCardFreeMemory(SCardContext hContext, Pointer pvMem) {
			return delegate.SCardFreeMemory(hContext, pvMem);
		}
		public NativeLong SCardListReaderGroups(SCardContext hContext, ByteBuffer mszGroups, IntByReference pcchGroups) {
			NativeLongByReference longNumGroups = new NativeLongByReference(new NativeLong(pcchGroups.getValue()));  // inout
			NativeLong r = delegate.SCardListReaderGroups(hContext, mszGroups, longNumGroups);
			pcchGroups.setValue(longNumGroups.getValue().intValue());
			return r;
		}
		public NativeLong SCardCancel(SCardContext hContext) {
			return delegate.SCardCancel(hContext);
		}
		public NativeLong SCardIsValidContext (SCardContext hContext) {
			return delegate.SCardIsValidContext(hContext);
		}
	}
	public static class WinscardLibInfo {
		public final WinscardLibrary lib;
		public final ScardIoRequest SCARD_PCI_T0;
		public final ScardIoRequest SCARD_PCI_T1;
		public final ScardIoRequest SCARD_PCI_RAW;
		private final Class<? extends SCardReaderState> scardReaderStateClass;
		private final Constructor<? extends SCardReaderState> scardReaderStateConstructor;
		public WinscardLibInfo(WinscardLibrary lib, ScardIoRequest SCARD_PCI_T0, ScardIoRequest SCARD_PCI_T1, ScardIoRequest SCARD_PCI_RAW, Class<? extends SCardReaderState> scardReaderStateClass, Constructor<? extends SCardReaderState> scardReaderStateConstructor) {
			this.lib = lib;
			this.SCARD_PCI_T0 = SCARD_PCI_T0;
			this.SCARD_PCI_T1 = SCARD_PCI_T1;
			this.SCARD_PCI_RAW = SCARD_PCI_RAW;
			this.scardReaderStateClass = scardReaderStateClass;
			this.scardReaderStateConstructor = scardReaderStateConstructor;
		}
		public SCardReaderState[] createSCardReaderStateArray(int n) {
			return (SCardReaderState[]) Array.newInstance(scardReaderStateClass, n);
		}
		public SCardReaderState createSCardReaderState() {
			try {
				return scardReaderStateConstructor.newInstance();
			} catch (InstantiationException e) {
				throw new IllegalStateException();
			} catch (IllegalAccessException e) {
				throw new IllegalStateException();
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException();
			} catch (InvocationTargetException e) {
				throw new IllegalStateException();
			}
		}
	}
	public static WinscardLibInfo openLib() {
		String libraryName = Platform.isWindows() ? WINDOWS_PATH : Platform.isMac() ? MAC_PATH : PCSC_PATH;
		WinscardLibrary lib;
		if (Platform.isWindows() || Platform.isMac()) {
			HashMap<Object, Object> options = new HashMap<Object, Object>();
			final Set<String> asciiSuffixNames = new HashSet<String>();
			asciiSuffixNames.addAll(Arrays.asList("SCardListReaderGroups", "SCardListReaders", "SCardGetStatusChange", "SCardConnect", "SCardStatus"));
			options.put(Library.OPTION_FUNCTION_MAPPER, new FunctionMapper() {
				@Override public String getFunctionName(NativeLibrary library, Method method) {
					String name = method.getName();
					if (asciiSuffixNames.contains(name))
						name = name + 'A';
					return name;
				}
			});
			lib = (WinscardLibrary) Native.loadLibrary(libraryName, WinscardLibrary.class, options);
		} else {
			PcscLiteLibrary linuxPcscLib = (PcscLiteLibrary) Native.loadLibrary(libraryName, PcscLiteLibrary.class);
			lib = new PcscLiteAdapter(linuxPcscLib);
		}
		Class<? extends SCardReaderState> scardReaderStateClass;
		Constructor<? extends SCardReaderState> scardReaderStateConstructor;
		try {
			scardReaderStateClass = 
				Platform.isWindows() ? WinscardSCardReaderState.class :
				Platform.isMac() ? OSXSCardReaderState.class:
				LinuxSCardReaderState.class;
			scardReaderStateConstructor = scardReaderStateClass.getConstructor();
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException();
		} catch (SecurityException e) {
			throw new IllegalStateException();
		}
		NativeLibrary nativeLibrary = NativeLibrary.getInstance(libraryName);
		// SCARD_PCI_* is #defined to the following symbols (both pcsclite and winscard)
		ScardIoRequest SCARD_PCI_T0 = new ScardIoRequest(nativeLibrary.getGlobalVariableAddress("g_rgSCardT0Pci"));
		ScardIoRequest SCARD_PCI_T1 = new ScardIoRequest(nativeLibrary.getGlobalVariableAddress("g_rgSCardT1Pci"));
		ScardIoRequest SCARD_PCI_RAW = new ScardIoRequest(nativeLibrary.getGlobalVariableAddress("g_rgSCardRawPci"));
		return new WinscardLibInfo(lib, SCARD_PCI_T0, SCARD_PCI_T1, SCARD_PCI_RAW, scardReaderStateClass, scardReaderStateConstructor);
	}
}
