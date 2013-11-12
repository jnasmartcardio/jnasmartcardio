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