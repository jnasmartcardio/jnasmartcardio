/*
 * To the extent possible under law, contributors have waived all
 * copyright and related or neighboring rights to work.
 */
package jnasmartcardio;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardNotPresentException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactorySpi;

import jnasmartcardio.Winscard.SCardReaderState;

import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;


public class Smartcardio extends Provider {
	
	private static final long serialVersionUID = 1L;
	
	static final int MAX_ATR_SIZE = 33;

	public static final String PROVIDER_NAME = "JNA2PCSC";
	
	public Smartcardio() {
		super(PROVIDER_NAME, 0.0d, "JNA-to-PCSC Provider");
		put("TerminalFactory.PC/SC", JnaTerminalFactorySpi.class.getName());
	}
	
	public static class JnaTerminalFactorySpi extends TerminalFactorySpi {
		public static final int SCARD_SCOPE_USER = 0;
		public static final int SCARD_SCOPE_TERMINAL = 1;
		public static final int SCARD_SCOPE_SYSTEM = 2;
		private final Winscard.WinscardLibInfo libInfo;
		private final Winscard.SCardContext scardContext;
		private boolean isClosed;
		
		/**
		 * Likely exceptions
		 * <ul>
		 * <li>IllegalStateException(JnaPCSCException(
		 * {@link WinscardConstants#SCARD_E_NO_READERS_AVAILABLE})) the Daemon
		 * is not running (Windows 8, Linux). On Windows 8, the daemon is shut
		 * down when there are no readers plugged in. New PCSC versions (at
		 * least 1.7) also allow no new connections when there are no readers
		 * plugged in.
		 * <li>IllegalStateException(JnaPCSCException(
		 * {@link WinscardConstants#SCARD_E_NO_SERVICE})) the Daemon is not
		 * running (OS X). On OS X (pcscd 1.4), the daemon is shut down when
		 * there are no readers plugged in, and the library gives this error.
		 * Can also happen on Windows when you don't have permission.
		 * </ul>
		 */
		public JnaTerminalFactorySpi(Object parameter) {
			this.libInfo = Winscard.openLib();
			Winscard.SCardContextByReference phContext = new Winscard.SCardContextByReference();
			try {
				check("SCardEstablishContext", libInfo.lib.SCardEstablishContext(SCARD_SCOPE_SYSTEM, null, null, phContext));
			} catch (JnaPCSCException e) {
				throw new IllegalStateException(e);
			}
			this.scardContext = phContext.getValue();
		}
		
		public JnaTerminalFactorySpi(Winscard.WinscardLibInfo libInfo, Winscard.SCardContext scardContext) {
			this.libInfo = libInfo;
			this.scardContext = scardContext;
		}
		@Override public CardTerminals engineTerminals() {
			return new JnaCardTerminals(libInfo, scardContext);
		}
		@Override public void finalize() throws CardException {
			close();
		}
		public synchronized void close() throws CardException {
			if (isClosed)
				return;
			isClosed = true;
			check("SCardReleaseContext", libInfo.lib.SCardReleaseContext(scardContext));
		}
	}

	public static class JnaCardTerminals extends CardTerminals {
		private final Winscard.SCardContext scardContext;
		private final Winscard.WinscardLibInfo libInfo;
		/** The readers that waitForChange observed in its last invocation. */
		private final List<SCardReaderState> knownReaders;
		/**
		 * Readers that previously existed. Stored until the next
		 * {@link #waitForChange(long)} call.
		 */
		private final List<SCardReaderState> zombieReaders;
		private boolean knownReadersChanged;
		/**
		 * Whether to use the PNP device to etect when new readers are plugged
		 * in. Unfortunately, this is now almost useless, because the smartcard
		 * service exits and gives errors when there are no readers.
		 */
		private final boolean usePnp = true;
		public JnaCardTerminals(Winscard.WinscardLibInfo libInfo, Winscard.SCardContext scardContext) {
			this.libInfo = libInfo;
			this.scardContext = scardContext;
			this.knownReaders = new ArrayList<SCardReaderState>();
			this.zombieReaders = new ArrayList<SCardReaderState>();
			if (usePnp) {
				SCardReaderState pnpReaderState = libInfo.createSCardReaderState();
				pnpReaderState.setReaderName(WinscardConstants.PNP_READER_ID);
				knownReaders.add(pnpReaderState);
			}
		}
		@Override public List<CardTerminal> list(State state) throws CardException {
			if (null == state)
				throw new NullPointerException("State must be non-null. To get all terminals, just call zero-arg list().");
			if (state == State.CARD_REMOVAL || state == State.CARD_INSERTION) {
				List<CardTerminal> r = new ArrayList<CardTerminal>();
				for (int i = 0; i < knownReaders.size(); i++) {
					SCardReaderState readerState = knownReaders.get(i);
					if (usePnp && i == 0)
						continue;
					boolean wasPresent = 0 != (readerState.getCurrentState() & WinscardConstants.SCARD_STATE_PRESENT);
					boolean isPresent = 0 != (readerState.getEventState() & WinscardConstants.SCARD_STATE_PRESENT);
					int oldCounter = (readerState.getCurrentState() >> 16) & 0xffff;
					int newCounter = (readerState.getEventState() >> 16) & 0xffff;
					boolean cardInserted = ! wasPresent && isPresent ||
						isPresent && oldCounter < newCounter ||
						oldCounter + 1 < newCounter;
					boolean cardRemoved = wasPresent && !isPresent ||
						! isPresent && oldCounter < newCounter ||
						oldCounter + 1 < newCounter;
					boolean shouldAdd = state == State.CARD_INSERTION && cardInserted ||
							state == State.CARD_REMOVAL && cardRemoved;
					if (shouldAdd)
						r.add(new JnaCardTerminal(libInfo, scardContext, readerState.getReaderName()));
				}
				if (state == State.CARD_REMOVAL) {
					for (int i = 0; i < zombieReaders.size(); i++) {
						SCardReaderState readerState = zombieReaders.get(i);
						boolean wasPresent = 0 != (readerState.getCurrentState() & WinscardConstants.SCARD_STATE_PRESENT);
						if (wasPresent)
							r.add(new JnaCardTerminal(libInfo, scardContext, readerState.getReaderName()));
					}
				}
				return r;
			}

			List<String> readerNames = listReaderNames();
			if (readerNames.isEmpty())
				return Collections.emptyList();
			List<String> filteredReaderNames;
			if (state == State.ALL) {
				filteredReaderNames = readerNames;
			} else {
				SCardReaderState[] readers = libInfo.createSCardReaderStateArray(readerNames.size());
				libInfo.createSCardReaderState().toArray(readers);
				for (int i = 0; i < readers.length; i++) {
					readers[i].setReaderName(readerNames.get(i));
				}
				check("SCardGetStatusChange", libInfo.lib.SCardGetStatusChange(scardContext, 0, readers, readers.length));
				filteredReaderNames = new ArrayList<String>();
				boolean wantPresent = state == State.CARD_PRESENT;
				for (int i = 0; i < readers.length; i++) {
					boolean isPresent = 0 != (WinscardConstants.SCARD_STATE_PRESENT & readers[i].getEventState());
					if (wantPresent == isPresent)
						filteredReaderNames.add(readers[i].getReaderName());
				}
			}
			CardTerminal[] cardTerminals = new CardTerminal[filteredReaderNames.size()];
			for (int i = 0; i < filteredReaderNames.size(); i++) {
				String name = filteredReaderNames.get(i);
				cardTerminals[i] = new JnaCardTerminal(libInfo, scardContext, name);
			}
			return Collections.unmodifiableList(Arrays.asList(cardTerminals));
		}

		/** Simple wrapper around SCardListReaders. */
		private List<String> listReaderNames() throws JnaPCSCException {
			IntByReference pcchReaders = new IntByReference();
			byte[] mszReaders = null;
			long err;
			ByteBuffer mszReaderGroups = ByteBuffer.allocate("SCard$AllReaders".length() + 2);
			mszReaderGroups.put("SCard$AllReaders".getBytes(Charset.forName("ascii")));
			while (true) {
				err = libInfo.lib.SCardListReaders(scardContext, mszReaderGroups, null, pcchReaders).longValue();
				if (err != 0)
					break;
				mszReaders = new byte[pcchReaders.getValue()];
				err = libInfo.lib.SCardListReaders(scardContext, mszReaderGroups, ByteBuffer.wrap(mszReaders), pcchReaders).longValue();
				if ((int)err != WinscardConstants.SCARD_E_INSUFFICIENT_BUFFER)
					break;
			}
			switch ((int)err) {
			case WinscardConstants.SCARD_S_SUCCESS:
				List<String> readerNames = pcsc_multi2jstring(mszReaders);
				return readerNames;
			case WinscardConstants.SCARD_E_NO_READERS_AVAILABLE:
			case WinscardConstants.SCARD_E_READER_UNAVAILABLE:
				return Collections.emptyList();
			default:
				check("SCardListReaders", err);
				throw new IllegalStateException();
			}
		}
		
		/**
		 * Helper function for {@link #waitForChange(long)}. Lists the readers
		 * and updates 3 variables:
		 * <ul>
		 * <li>Any new readers are appended to {@link #knownReaders}.
		 * <li>Any old readers are moved from {@link #knownReaders} to
		 * {@link #zombieReaders}.
		 * <li>If any change is made, {@link #knownReadersChanged} is set so
		 * that the JNA array-of-struct can be reallocated.
		 * </ul>
		 * 
		 * @return true if a reader was added or removed.
		 */
		private boolean updateKnownReaders() throws JnaPCSCException {
			boolean isReaderAddedOrRemoved = false;
			List<String> currentReaderNames = listReaderNames();
			HashSet<String> existingReaderNames = new HashSet<String>(knownReaders.size() - (usePnp?1:0));
			Iterator<SCardReaderState> it = knownReaders.iterator();
			if (usePnp) it.next();
			while (it.hasNext()) {
				SCardReaderState reader = it.next();
				existingReaderNames.add(reader.getReaderName());
				if (currentReaderNames.contains(reader.getReaderName()))
					continue;
				it.remove();
				reader.setEventState(0);
				zombieReaders.add(reader);
				isReaderAddedOrRemoved = true;
			}
			List<String> newReadersNames = new ArrayList<String>();
			for (String readerName: currentReaderNames) {
				if (existingReaderNames.contains(readerName))
					continue;
				newReadersNames.add(readerName);
			}
			if (! newReadersNames.isEmpty()) {
				SCardReaderState[] newReaders = libInfo.createSCardReaderStateArray(newReadersNames.size());
				libInfo.createSCardReaderState().toArray(newReaders);
				for (int i = 0; i < newReaders.length; i++)
					newReaders[i].setReaderName(newReadersNames.get(i));
				check("SCardGetStatusChange", libInfo.lib.SCardGetStatusChange(scardContext, 0, newReaders, newReaders.length));
				knownReaders.addAll(Arrays.asList(newReaders));
				isReaderAddedOrRemoved = true;
			}
			if (isReaderAddedOrRemoved)
				knownReadersChanged = true;
			return isReaderAddedOrRemoved;
		}

		/**
		 * Block until any card is inserted or removed, or until the timeout.
		 * 
		 * <p>
		 * Deviation from the Sun version: the first
		 * {@link #waitForChange(long)} call always returns immediately. In
		 * Sun's version, if the card is inserted between your {@link #list()}
		 * call and the first {@link #waitForChange(long)} call, then your
		 * application can wait forever.
		 * 
		 * <p>
		 * Note: this method returns early when any smartcard state has changed
		 * (e.g. smartcard becomes in-use or idle). The caller cannot observe
		 * these changes though. So the caller should be able to handle changes
		 * that appear spurious.
		 * 
		 * <p>
		 * Likely exceptions
		 * <ul>
		 * <li>JnaPCSCException(
		 * {@link WinscardConstants#SCARD_E_SERVICE_STOPPED}) On Windows 8+, the
		 * service shuts down immediately when the last reader is unplugged.
		 * Then, you have to start polling because there is no daemon to
		 * subscribe to.
		 * </ul>
		 */
		@Override public boolean waitForChange(long timeoutMs) throws CardException {
			if (timeoutMs < 0)
				throw new IllegalArgumentException("Negative timeout " + timeoutMs);
			else if (timeoutMs == 0)
				timeoutMs = WinscardConstants.INFINITE;

			zombieReaders.clear();
			// On Linux pcsclite 1.7.4, the PNP reader does not return
			// immediately when there is already a reader present that isn't in
			// the array. Strangely, it works fine in OS X.
			if (!usePnp || Platform.isLinux())
				if (updateKnownReaders())
					return true;  // # of readers changed; return early.

			if (knownReadersChanged) {
				knownReadersChanged = false;
				// allocate a contiguous array of struct, and copy
				SCardReaderState[] arr = libInfo.createSCardReaderStateArray(knownReaders.size());
				//knownReaders.get(0).toArray(knownReaders.toArray(arr));
				libInfo.createSCardReaderState().toArray(arr);
				for (int i = 0; i < knownReaders.size(); i++) {
					SCardReaderState oldReader = knownReaders.get(i);
					SCardReaderState newReader = arr[i];
					newReader.setReaderName(oldReader.getReaderName());
					newReader.setCurrentState(oldReader.getCurrentState());
					newReader.setEventState(oldReader.getEventState());
					newReader.setAtrLength(oldReader.getAtrLength());
					System.arraycopy(oldReader.getAtrArray(), 0, newReader.getAtrArray(), 0, oldReader.getAtrLength());
					knownReaders.set(i, newReader);
				}
			}
			for (SCardReaderState reader: knownReaders) {
				reader.setCurrentState(reader.getEventState());
				reader.setEventState(0);
			}
			SCardReaderState[] readers;
			if (knownReaders.isEmpty()) {
				// create array containing null, to avoid JNA exception:
				// Structure array must have non-zero length
				readers = libInfo.createSCardReaderStateArray(1);
			} else {
				readers = knownReaders.toArray(libInfo.createSCardReaderStateArray(knownReaders.size()));
			}
			//timeoutMs = 2000;
			NativeLong statusError = libInfo.lib.SCardGetStatusChange(scardContext, (int)timeoutMs, readers, readers.length);
			if (WinscardConstants.SCARD_E_TIMEOUT == (int)statusError.longValue())
				return false;
			else check("SCardGetStatusChange", statusError);
			for (SCardReaderState reader: readers) {
				String name = reader.getReaderName();
				int currentState = reader.getCurrentState();
				int eventState = reader.getEventState();
				System.out.format("%x -> %x %s%n", currentState, eventState,name);
			}

			if (usePnp) {
				boolean pnpChange = 0 != (knownReaders.get(0).getEventState() & WinscardConstants.SCARD_STATE_CHANGED);
				if (pnpChange)
					updateKnownReaders();
			}
			return true;
		}
	}

	public static class JnaCardTerminal extends CardTerminal {
		private final Winscard.WinscardLibInfo libInfo;
		private final Winscard.SCardContext scardContext;
		private final String name;
		public static final int SCARD_SHARE_EXCLUSIVE = 1;
		public static final int SCARD_SHARE_SHARED = 2;
		public static final int SCARD_SHARE_DIRECT = 3;
		public static final int SCARD_PROTOCOL_T0 = 1;
		public static final int SCARD_PROTOCOL_T1 = 2;
		public static final int SCARD_PROTOCOL_RAW = 4;
		public static final int SCARD_PROTOCOL_T15 = 8;
		public static final int SCARD_PROTOCOL_ANY = SCARD_PROTOCOL_T0 | SCARD_PROTOCOL_T1;
		
		public static final int SCARD_UNKNOWN = 0x01;
		public static final int SCARD_ABSENT = 0x02;
		public static final int SCARD_PRESENT = 0x04;
		public static final int SCARD_SWALLOWED = 0x08;
		public static final int SCARD_POWERED = 0x10;
		public static final int SCARD_NEGOTIABLE = 0x20;
		public static final int SCARD_SPECIFIC = 0x40;
		
		public JnaCardTerminal(Winscard.WinscardLibInfo libInfo, Winscard.SCardContext scardContext, String name) {
			this.libInfo = libInfo;
			this.scardContext = scardContext;
			this.name = name;
		}
		@Override public String getName() {return name;}
		@Override public Card connect(String protocol) throws CardException {
			int dwPreferredProtocols;
			if ("T=0".equals(protocol)) dwPreferredProtocols = SCARD_PROTOCOL_T0;
			else if ("T=1".equals(protocol)) dwPreferredProtocols = SCARD_PROTOCOL_T1;
			else if ("*".equals(protocol)) dwPreferredProtocols = SCARD_PROTOCOL_ANY;
			else if ("T=CL".equals(protocol)) dwPreferredProtocols = 0;  // and SCARD_SHARE_DIRECT
			else throw new IllegalArgumentException("Protocol should be one of T=0, T=1, *, T=CL. Got " + protocol);
			Winscard.SCardHandleByReference phCard = new Winscard.SCardHandleByReference();
			IntByReference pdwActiveProtocol = new IntByReference();
	
			long err = libInfo.lib.SCardConnect(scardContext, name, SCARD_SHARE_SHARED, dwPreferredProtocols, phCard, pdwActiveProtocol).longValue();
			switch ((int)err) {
			case WinscardConstants.SCARD_S_SUCCESS:
				Winscard.SCardHandle scardHandle = phCard.getValue();
				IntByReference readerLength = new IntByReference();
				IntByReference currentState = new IntByReference();
				IntByReference currentProtocol = new IntByReference();
				ByteBuffer atrBuf = ByteBuffer.allocate(Smartcardio.MAX_ATR_SIZE);
				IntByReference atrLength = new IntByReference(Smartcardio.MAX_ATR_SIZE);
				check("SCardStatus", libInfo.lib.SCardStatus(scardHandle, null, readerLength, currentState, currentProtocol, atrBuf, atrLength).longValue());
				int readerLengthInt = readerLength.getValue();
				ByteBuffer readerName = ByteBuffer.allocate(readerLengthInt);
				check("SCardStatus", libInfo.lib.SCardStatus(scardHandle, readerName, readerLength, currentState, currentProtocol, atrBuf, atrLength).longValue());
				int atrLengthInt = atrLength.getValue();
				atrBuf.limit(atrLengthInt);
				byte[] atrBytes = new byte[atrBuf.remaining()];
				atrBuf.get(atrBytes);
				ATR atr = new ATR(atrBytes);
				int currentProtocolInt = currentProtocol.getValue();
				return new JnaCard(libInfo, scardContext, scardHandle, atr, currentProtocolInt);
			case WinscardConstants.SCARD_W_REMOVED_CARD:
				throw new JnaCardNotPresentException(err, "Card not present.");
			default:
				check("SCardConnect", err);
				throw new RuntimeException("Should not reach here.");
			}
		}
		@Override public boolean isCardPresent() throws CardException {
			int dwPreferredProtocols = SCARD_PROTOCOL_ANY;
			Winscard.SCardHandleByReference phCard = new Winscard.SCardHandleByReference();
			IntByReference pdwActiveProtocol = new IntByReference();
			SCardReaderState[] rgReaderStates = libInfo.createSCardReaderStateArray(1);
			rgReaderStates[0] = libInfo.createSCardReaderState();
			rgReaderStates[0].setReaderName(name);
			// TODO: on Windows, just call SCardLocateCards
			long err = libInfo.lib.SCardConnect(scardContext, name, SCARD_SHARE_DIRECT, dwPreferredProtocols, phCard, pdwActiveProtocol).longValue();
			if ((int)err == WinscardConstants.SCARD_E_NO_SMARTCARD)
				return false;
			else check("SCardConnect", err);
			Winscard.SCardHandle scardHandle = phCard.getValue();
			try {
				IntByReference readerLength = new IntByReference();
				IntByReference currentState = new IntByReference();
				IntByReference currentProtocol = new IntByReference();
				ByteBuffer atrBuf = ByteBuffer.allocate(Smartcardio.MAX_ATR_SIZE);
				IntByReference atrLength = new IntByReference(Smartcardio.MAX_ATR_SIZE);
				check("SCardStatus", libInfo.lib.SCardStatus(scardHandle, null, readerLength, currentState, currentProtocol, atrBuf, atrLength).longValue());
				int currentStateInt = currentState.getValue();
				return 0 != (currentStateInt & SCARD_PRESENT);
			} finally {
				libInfo.lib.SCardDisconnect(scardHandle, JnaCard.SCARD_LEAVE_CARD);
			}
		}
		private boolean waitHelper(long timeoutMs, boolean cardPresent) throws JnaPCSCException {
			if (timeoutMs < 0)
				throw new IllegalArgumentException("Negative timeout " + timeoutMs);
			if (timeoutMs == 0)
				timeoutMs = WinscardConstants.INFINITE;
			SCardReaderState[] rgReaderStates = libInfo.createSCardReaderStateArray(1);
			SCardReaderState readerState = rgReaderStates[0] = libInfo.createSCardReaderState();
			rgReaderStates[0].setReaderName(name);
			int remainingTimeout = (int) timeoutMs;
			while (cardPresent != (0 != (readerState.getEventState() & WinscardConstants.SCARD_STATE_PRESENT))) {
				long startTime = System.currentTimeMillis();
				long err = libInfo.lib.SCardGetStatusChange(scardContext, remainingTimeout, rgReaderStates, rgReaderStates.length).longValue();
				long endTime = System.currentTimeMillis();
				if (WinscardConstants.SCARD_E_TIMEOUT == (int)err)
					return false;
				check("SCardGetStatusChange", err);
				readerState.setCurrentState(readerState.getEventState());
				readerState.setEventState(0);
				if (remainingTimeout != WinscardConstants.INFINITE) {
					if (remainingTimeout < endTime - startTime)
						return false;
					remainingTimeout -= endTime - startTime;
				}
			}
			return true;
		}
		@Override public boolean waitForCardAbsent(long timeoutMs) throws CardException {
			return waitHelper(timeoutMs, false);
		}
		@Override public boolean waitForCardPresent(long timeoutMs) throws CardException {
			return waitHelper(timeoutMs, true);
		}
	}

	public static class JnaCard extends Card {
		private final Winscard.WinscardLibInfo libInfo;
		private final Winscard.SCardContext scardContext;
		private final Winscard.SCardHandle scardHandle;
		private final ATR atr;
		/**
		 * One of {@link JnaCardTerminal#SCARD_PROTOCOL_RAW},
		 * {@link JnaCardTerminal#SCARD_PROTOCOL_T0},
		 * {@link JnaCardTerminal#SCARD_PROTOCOL_T1}
		 */
		private final int protocol;
		public JnaCard(Winscard.WinscardLibInfo libInfo, Winscard.SCardContext scardContext, Winscard.SCardHandle scardHandle, ATR atr, int protocol) {
			this.libInfo = libInfo;
			this.scardContext = scardContext;
			this.scardHandle = scardHandle;
			this.atr = atr;
			this.protocol = protocol;
			getProtocol();  // make sure it is valid.
		}

		@Override public void beginExclusive() throws CardException {
			check("SCardBeginTransaction", libInfo.lib.SCardBeginTransaction(scardHandle));
		}
		public static final int SCARD_LEAVE_CARD = 0;
		public static final int SCARD_RESET_CARD = 1;
		public static final int SCARD_UNPOWER_CARD = 2;
		public static final int SCARD_EJECT_CARD = 3;
		@Override public void endExclusive() throws CardException {
			check("SCardEndTransaction", libInfo.lib.SCardEndTransaction(scardHandle, SCARD_LEAVE_CARD));
			// TODO: handle error SCARD_W_RESET_CARD esp. in Windows
		}

		@Override public void disconnect(boolean reset) throws CardException {
			int dwDisposition = reset ? SCARD_RESET_CARD : SCARD_LEAVE_CARD;
			check("SCardDisconnect", libInfo.lib.SCardDisconnect(scardHandle, dwDisposition));
		}

		@Override public ATR getATR() {return atr;}
		@Override public String getProtocol() {
			switch (protocol) {
			case JnaCardTerminal.SCARD_PROTOCOL_T0: return "T=0";
			case JnaCardTerminal.SCARD_PROTOCOL_T1: return "T=1";
			case JnaCardTerminal.SCARD_PROTOCOL_RAW: return "T=CL";  // TODO: is this right?
			}
			throw new IllegalStateException("Unknown protocol: " + protocol);
		}

		@Override public CardChannel getBasicChannel() {
			return new JnaCardChannel(this, (byte)0, this.protocol == JnaCardTerminal.SCARD_PROTOCOL_T0);
		}

		@Override public CardChannel openLogicalChannel() throws CardException {
			// manage channel: request a new logical channel from 0x01 to 0x13
//			ByteBuffer command = JnaCardChannel.prepareRequest(new CommandAPDU(0, 0x70, 0x00, 0x00, 1));
			
			byte cla = 1;
			return new JnaCardChannel(this, cla, this.protocol == JnaCardTerminal.SCARD_PROTOCOL_T0);
		}
		
		/**
		 * @param controlCode
		 *            one of the IOCTL_SMARTCARD_* constants from WinSmCrd.h
		 */
		@Override
		public byte[] transmitControlCommand(int controlCode, byte[] arg1) throws CardException {
			// there's no way from the API to know how big a receive buffer to use.
			// Sun uses 8192 bytes, so we'll do the same.
			ByteBuffer receiveBuf = ByteBuffer.allocate(8192);
			IntByReference lpBytesReturned = new IntByReference();
			ByteBuffer arg1Wrapped = ByteBuffer.wrap(arg1);
			check("SCardControl", libInfo.lib.SCardControl(scardHandle, controlCode, arg1Wrapped, arg1.length, receiveBuf, receiveBuf.remaining(), lpBytesReturned));
			int bytesReturned = lpBytesReturned.getValue();
			receiveBuf.limit(bytesReturned);
			byte[] r = new byte[bytesReturned];
			receiveBuf.get(r);
			return r;
		}
	}

	public static class JnaCardChannel extends CardChannel {
		private final JnaCard card;
		private final int channel;
		private boolean isClosed;
		private boolean convertToShortApdus;
		/**
		 * Ignore Le for {@link #transmit(CommandAPDU)}. This means that when
		 * transmit needs to allocate a ByteBuffer, it will always include extra
		 * bytes in case the card ignores Le. Otherwise, when user expects Le of
		 * 0 but the card transmits anyway, we'll get SCARD_E_NOT_TRANSACTED.
		 */
		private boolean ignoreLeWhenAllocating = true;
		public JnaCardChannel(JnaCard card, int cla, boolean convertToShortApdus) {
			this.card = card;
			this.channel = cla;
			this.convertToShortApdus = convertToShortApdus;
		}
		@Override public void close() throws CardException {
			if (isClosed)
				return;
			isClosed = true;
			if (channel != 0) {
				ByteBuffer command = ByteBuffer.wrap(new CommandAPDU(0, 0x70, 0x80, channel).getBytes());
				ByteBuffer response = ByteBuffer.allocate(2);
				transmitRaw(command, response);
				response.rewind();
				int sw = response.getShort();
				if (sw != 0x6000) {
					throw new JnaCardException(sw, "Could not close channel.");
				}
			}
		}
		@Override public Card getCard() {return card;}
		@Override public int getChannelNumber() {return channel & 0xff;}
		@Override public ResponseAPDU transmit(CommandAPDU command) throws CardException {
			if (command == null) {
				throw new IllegalArgumentException("command is null");
			}
			byte[] commandCopy = command.getBytes();
			ByteBuffer response = transmitImpl(commandCopy, null);

			System.out.format("%d vs %s%n",response.position(), 2+command.getNe());
			ResponseAPDU responseApdu = convertResponse(response);
			return responseApdu;
		}

		/**
		 * Transmit the given command APDU and store the response APDU. Returns
		 * the length of the response APDU.
		 * 
		 * <p>
		 * Reminder: there are several forms of APDU:<br>
		 * 1. CLA INS P1 P2. No body, no response body.<br>
		 * 2s. CLA INS P1 P2 Le. No body. Le in [1,00 (256)]<br>
		 * 2e. CLA INS P1 P2 00 Le1 Le2. No body. Le in [1,0000 (65536)]<br>
		 * T=0: use Le = 00<br>
		 * 3s. CLA INS P1 P2 Lc &lt;body&gt;. No response. Lc in [1,255]<br>
		 * 3e. CLA INS P1 P2 00 Lc1 Lc2 &lt;body&gt;. No response. Lc in [1,ffff]<br>
		 * T=0: if Nc &lt;= 255, then use short form. Else, use envelope.<br>
		 * 4s. CLA INS P1 P2 Lc &lt;body&gt; Le. No response. Lc in [1,00]. Le in [1,ff]<br>
		 * 4e. CLA INS P1 P2 00 Lc1 Lc2 &lt;body&gt; 00 Le1 Le2. Lc in [1,0000]. Le in [1,ffff]
		 * 
		 * <p>
		 * This method handles:
		 * <ul>
		 * <li>If T=0, then convert APDU to T=0 TPDU (ISO 7816-3). In
		 * particular, if T=0 and there is request data, then strip the Le field
		 * <li>If sw = 61xx, then call c0 get response and concatenate
		 * <li>If sw = 6cxx, then retransmit with Le = xx
		 * </ul>
		 * Q: Should it also handle
		 * <ul>
		 * <li>Command chaining (if mentioned in historic bytes) (bit 5 of cla =
		 * true)
		 * <li>Envelope (ins = c2 or c3)
		 * </ul>
		 * 
		 * <p>
		 * T=0 protocol: 3 cases
		 * <ul>
		 * <li>CLA INS P1 P2. Response will always be 2 bytes.
		 * <li>CLA INS P1 P2 Le. Response will be up to Le+2 bytes. (Le=0 means
		 * 256 bytes). Use get response commands to get all the data.
		 * <li>CLA INS P1 P2 Lc. &lt;outgoing data&gt. Response will always be 2
		 * bytes. Long commands need to be enclosed in envelope commands.
		 * </ul>
		 * 
		 * <p>
		 * T=1 protocol: CLA INS P1 P2 [Lc Data] [Le].<br>
		 * Lc is either 01-ff or 000001-00ffff.<br>
		 * Le is either 00-ff (00=256) or 0000-ffff. (0000=65536)
		 */
		@Override public int transmit(ByteBuffer command, ByteBuffer response) throws CardException {
			if (command == null) {
				throw new IllegalArgumentException("command is null");
			}
			if (response == null) {
				throw new IllegalArgumentException("response is null");
			}
			byte[] copy = new byte[command.remaining()];
			command.get(copy);
			command = ByteBuffer.wrap(copy);
			int startPosition = response.position();
			transmitImpl(copy, response);
			int endPosition = response.position();
			return endPosition - startPosition;
		}
		private int getExtendedLc(byte[] commandApdu) {
			if (commandApdu.length == 7)
				return 0;
			int len = (commandApdu[5] & 0xff << 8) | commandApdu[6] & 0xff;
			assert len != 0;
			return len;
		}
		private int getExtendedLe(byte[] commandApdu, int Lc) {
			int len;
			if (Lc == 0)
				len = (commandApdu[5] & 0xff << 8) | commandApdu[6] & 0xff;
			else if (commandApdu.length == 7 + Lc) {
				return 0;
			} else {
				assert 0 == commandApdu[7 + Lc];
				len = (commandApdu[8 + Lc] & 0xff << 8) | commandApdu[9 + Lc] & 0xff;
			}
			if (len == 0)
				len = 65536;
			return len;
		}
		private int getShortLc(byte[] commandApdu) {
			if (commandApdu.length <= 5)
				return 0;
			int len = commandApdu[4] & 0xff;
			assert len != 0;
			return len;
		}
		private int getShortLe(byte[] commandApdu, int Lc) {
			int len;
			if (commandApdu.length <= 5) {
				return 0;
			} else if (Lc == 0) {
				len = commandApdu[4] & 0xff;
			} else if (commandApdu.length <= 5 + Lc) {
				return 0;
			} else {
				len = commandApdu[5 + Lc] & 0xff;
			}
			if (len == 0)
				len = 65536;
			return len;
		}
		private boolean isExtendedApdu(byte[] commandApdu) {
			return commandApdu.length >= 7 && commandApdu[4] == 0;
		}
		private ByteBuffer transmitImpl(byte[] copy, ByteBuffer response) throws JnaPCSCException {
			copy[0] = getClassByte(copy[0], channel);
			boolean isExtendedApdu = isExtendedApdu(copy);
			final int originalLc = isExtendedApdu ? getExtendedLc(copy) : getShortLc(copy);
			final int originalLe = isExtendedApdu ? getExtendedLe(copy, originalLc) : getShortLe(copy, originalLc);
			ByteBuffer command = ByteBuffer.wrap(copy);
			if (convertToShortApdus && isExtendedApdu && originalLc == 0) {
				// 2e. CLA INS P1 P2 00 Le1 Le2. Convert to Le=00.
				copy[4] = originalLe > 256 ? (byte) 0x00 : (byte)originalLe;
				command.limit(5);
			} else if (convertToShortApdus && isExtendedApdu && 0 < originalLc && originalLc <= 255) {
				// 4e, with small body. CLA INS P1 P2 00 00 Lc2 <body> 00 Le1 Le2.
				// 3e, with small body. CLA INS P1 P2 00 00 Lc2 <body>
				System.arraycopy(copy, 7, copy, 5, originalLc);
				command.limit(5 + originalLc);
			} else if (convertToShortApdus && isExtendedApdu && 256 <= originalLc) {
				// 4e, with big body. CLA INS P1 P2 00 Lc1 Lc2 <body> 00 Le1 Le2.
				// 3e, with big body. CLA INS P1 P2 00 Lc1 Lc2 <body>
				// TODO: wrap in envelope command
				System.arraycopy(copy, 7, copy, 5, originalLc);
				command.limit(5 + originalLc);
				throw new IllegalArgumentException("Can't transmit big bodies with T=0. (you'll need to do your own envelope)");
			} else if (convertToShortApdus && ! isExtendedApdu && originalLc > 0 && originalLe > 0) {
				// 4s: CLA INS P1 P2 Lc <body> Le
				command.limit(5 + originalLc);
			}
			int commandPosition = command.position();
			int commandLimit = command.limit();
	
			boolean isResponseFinal = response != null;
			final int originalPos = response == null ? 0 : response.position();
			int Le = originalLe;
			while (true) {
				if (isResponseFinal) {
				} else if (response == null) {
					int responseBufferSize = ignoreLeWhenAllocating ? Math.max(8192, Le + 2) : Le + 2;
					response = ByteBuffer.allocate(responseBufferSize);
				} else if (response != null && ignoreLeWhenAllocating) {
					int neededCapacity = Math.max(Le + 2, 4096);
					if (response.remaining() < neededCapacity) {
						ByteBuffer oldResponse = response;
						oldResponse.flip();
						response = ByteBuffer.allocate(response.position() + neededCapacity);
						response.put(oldResponse);
					}
				}
				int posBeforeTransmit = response.position();
				transmitRaw(command, response);
				response.position(response.position() - 2);
				byte sw1 = response.get();
				byte sw2 = response.get();
				if (0x6c == sw1 && copy[4] != 0) {
					int Na = 0x00 == sw2 ? 256 : sw2 & 0xff;
					copy[commandLimit - 1] = (byte) Na;
					response.position(posBeforeTransmit);
					command.position(commandPosition);
					command.limit(commandLimit);
					Le = Na;
					response.limit(response.limit() - 2);
				} else if (0x61 == sw1) {
					command.position(commandPosition + 1);
					command.put((byte) 0xc0);
					command.put((byte) 0x00);
					command.put((byte) 0x00);
					if (isExtendedApdu) {
						Le = 4096;
						command.put((byte) 0x00);
						command.put((byte) (Le >> 8));
						command.put((byte) Le);
					} else {
						Le = 256;
						command.put((byte) Le);
					}
					command.limit(command.position());
					command.position(commandPosition);
					response.limit(response.limit() - 2);
				} else {
					break;
				}
			}
			return response;
		}
		static byte getClassByte(byte origCla, int channelNumber) {
			if ((0xe0 & origCla) != 0 && (0xc0 & origCla) != 0x40)
				// Not an interindustry class; don't touch it.
				return origCla;
			int cla;
			// 7816-4/2005 5.1.1 Class byte
			if (0 <= channelNumber && channelNumber <= 3) {
				// First interindustry values of CLA: channel is bottom 2 bits
				cla = (origCla & 0x10) | channelNumber;
			} else if (0x04 <= channelNumber && channelNumber <= 0x13) {
				// Further interindustry values of CLA: channel is 4 + bottom 4 bits
				int mask = channelNumber - 4;
				cla = (origCla & 0x70) | mask | 0x40;
			} else {
				throw new RuntimeException("Bad channel number; expected 0-19; got " + channelNumber);
			}
			return (byte) cla;
		}
		private static ResponseAPDU convertResponse(ByteBuffer responseBuf) {
			byte[] responseBytes = new byte[responseBuf.position()];
			responseBuf.rewind();
			responseBuf.get(responseBytes);
			return new ResponseAPDU(responseBytes);
		}

		/**
		 * Transmit the given apdu. It's almost raw, except that it does set the
		 * CLA byte based on the channel.
		 */
		private int transmitRaw(ByteBuffer command, ByteBuffer response) throws JnaPCSCException {
			Winscard.ScardIoRequest pioSendPci;
			switch (card.protocol) {
			case JnaCardTerminal.SCARD_PROTOCOL_T0:
				pioSendPci = card.libInfo.SCARD_PCI_T0;
				break;
			case JnaCardTerminal.SCARD_PROTOCOL_T1:
				pioSendPci = card.libInfo.SCARD_PCI_T1;
				break;
			case JnaCardTerminal.SCARD_PROTOCOL_RAW:
				pioSendPci = card.libInfo.SCARD_PCI_RAW;
				break;
			default:
				throw new IllegalStateException("Don't know how to transmit for protocol " + card.protocol);	
			}
			int originalPosition = command.position();
			byte originalCla = command.get();
			command.position(originalPosition);
			byte cla = getClassByte(originalCla, channel);
			command.put(cla);
			command.position(originalPosition);
			IntByReference recvLength = new IntByReference(response.remaining());
			System.out.format("SCardTrasmit(command: %d bytes, receive buffer: %d bytes)%n", command.remaining(), response.remaining());
			check("SCardTransmit", card.libInfo.lib.SCardTransmit(card.scardHandle, pioSendPci, command, command.remaining(), null, response, recvLength));
			command.position(command.remaining());
			// TODO: retry to read all the data
			int recvLengthInt = recvLength.getValue();
			assert recvLengthInt >= 0;
			int newPosition = response.position() + recvLengthInt;
			response.position(newPosition);
			return recvLengthInt;
		}
	}

	public static class JnaPCSCException extends CardException {
		private static final long serialVersionUID = 1L;
		public final long code;
		public JnaPCSCException(String message) {this(0, message, null);}
		public JnaPCSCException(Throwable cause) {this(0, null, cause);}
		public JnaPCSCException(long code, String message) {this(code, message, null);}
		public JnaPCSCException(long code, String message, Throwable cause) {super(message, cause); this.code = code;}
	}

	public static class JnaCardNotPresentException extends CardNotPresentException {
		private static final long serialVersionUID = 1L;
		public final long code;
		public JnaCardNotPresentException(long code, String message) {super(message); this.code = code;}
	}

	public static class JnaCardException extends CardException {
		private static final long serialVersionUID = 1L;
		public final int sw;
		public JnaCardException(int sw, String message) {this(sw, message, null);}
		public JnaCardException(int sw, String message, Throwable cause) {super(message, cause); this.sw = sw;}
	}

	/**
	 * Named affectionately after the function I've seen in crash logs so often
	 * from libj2pcsc on OS X java7.
	 * @param  
	 */
	public static List<String> pcsc_multi2jstring(byte[] multiString, Charset charset) {
		List<String> r = new ArrayList<String>();
		int from = 0, to = 0;
		for (; to < multiString.length; to++) {
			if (multiString[to] != '\0')
				continue;
			if (from == to)
				return r;
			byte[] bytes = Arrays.copyOfRange(multiString, from, to);
			r.add(new String(bytes, charset));
			from = to + 1;
		}
		throw new IllegalArgumentException("Multistring must be end with a null-terminated empty string.");
	}

	public static List<String> pcsc_multi2jstring(byte[] multiString) {
		return pcsc_multi2jstring(multiString, Charset.forName("UTF-8"));
	}

	private static void check(String message, NativeLong code) throws JnaPCSCException {
		check(message, code.longValue());
	}

	private static void check(String message, long code) throws JnaPCSCException {
		if (code == 0)
			return;
		throw new JnaPCSCException(code, String.format("%s got response 0x%x (%s: %s)", message, code, WinscardConstants.ERROR_TO_VARIABLE_NAME.get((int)code), WinscardConstants.ERROR_TO_DESCRIPTION.get((int)code)));
	}
}
