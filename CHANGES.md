jnasmartcardio-0.2.3 (2014-07-09)
===
* [#19](https://github.com/jnasmartcardio/jnasmartcardio/issues/19) Card.transmitControlCommand failed on OS X.

jnasmartcardio-0.2.2 (2014-06-11)
===
* [#17](https://github.com/jnasmartcardio/jnasmartcardio/issues/17) Return to JDK 1.6 compatibility. Make JnaCardChannel and JnaCardTerminals no longer implement JDK 1.7-specific AutoCloseable.

jnasmartcardio-0.2.1 (2014-06-10)
===
* [#10](https://github.com/jnasmartcardio/jnasmartcardio/issues/10) Create a new SCardContext on every TerminalFactory.terminals() call to allow user to reconnect after daemon restarts. Since JnaCardTerminals now owns the SCardContext but the base class has no close() method, it now closes itself on finalize().
* [#16](https://github.com/jnasmartcardio/jnasmartcardio/issues/16) Make JnaCardTerminals and JnaCardChannel implement AutoCloseable, and require JDK 1.7. This is temporary; 0.2.2 removes the AutoCloseable use.
* [#12](https://github.com/jnasmartcardio/jnasmartcardio/pull/12) Simplify transmit: only handle the same retransmits that Sun does (61xx and 6cxx).
* Pass through the secure messaging indication and command chaining control in the CLA byte. The user must put them in the correct bits depending on the channel number.

jnasmartcardio-0.2.0 (2013-11-12)
===

* Add Linux support.
    * Fix dynamic library name on Linux (libpcsclite.so.1).
    * Fix CardTerminals.waitForChange(long) on Linux: don’t pack SCardReaderState, and query the readers before waiting for status change.
* Add Windows support.
    * Fix Windows symbol names e.g. SCardListReadersA.
    * Fix SCardContext and SCardHandle on 64-bit Java on Windows (and possibly 64-bit Java on OS X although I haven’t seen any crashes)
* Fix exceptions being thrown by CardTerminal.isCardPresent() by switching to a simpler implementation.
* Implement Card.openLogicalChannel().
* [#7](https://github.com/jnasmartcardio/jnasmartcardio/issues/7) Expand JNA requirement from 4.0.0 to [3.2.5, 4.0.0]

jnasmartcardio-0.1.0 (2013-10-24)
===
Initial release