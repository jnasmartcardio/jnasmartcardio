jna2pcsc
===
A re-implementation of the [`javax.smartcardio` API](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/). It allows you to communicate to a smart card (at the APDU level) from within Java.

In case you aren’t familiar with the technology, a smart card is a tiny CPU that fits inside a piece of plastic the size of a credit card. For example, you can buy the MyEID for about $15 to securely store and create signatures from a 2048-bit RSA private key. In order to use a smart card, you also have to buy a $15 USB smart card reader. Once you plug in the smart card reader, you use the winscard library built-in to Windows or the pcsclite library on OS X and Linux to communicate with the card. This library is an adapter that converts the native winscard API to the friendly `javax.smartcardio` interfaces.

Why?
---
The JRE already comes with implementations of `javax.smartcardio`. What’s wrong with it? If you are already using the smartcardio API, there are a couple reasons you might consider switching to a JNA solution:

* The [default smartcardio library in JRE 1.7 on 64-bit OS X is compiled incorrectly](http://mail.openjdk.java.net/pipermail/security-dev/2013-March/006913.html). In particular, `Terminal.isCardPresent()` always returns false, `Terminals.list()` occasionally causes SIGSEGV, and `Terminal.waitForCard(boolean, long)` and `Terminals.waitForChange(long)` don’t work.
* The default smartcardio library only calls `SCardEstablishContext` once. If the daemon isn’t up yet, then your process will never be able to connect to it again. This is a big problem because in Windows 8, OS X, and new versions of pcscd, the daemon is not started until a reader is plugged in, and it quits when there are no more readers.
* It’s easier to fix bugs in this project than it is to fix bugs in the libraries that are bundled with the JRE.

Installation
---
This is a Java project that only depends on JNA. We currently use Maven to download the dependency and then compile.

    mvn install

There are 3 ways to use this smartcard provider instead of the one that is bundled with JRE:

1. Modify &lt;java_home&gt;/jre/lib/security/java.security; replace `security.provider.9=sun.security.smartcardio.SunPCSC` with `security.provider.9=io.github.yonran.jna2pcsc.Smartcardio`. Then use `TerminalFactory.getDefault()`.
2. Override prop -Djava.security.properties=/path/to/override.java.security (see the provided file). Then use `TerminalFactory.getDefault()`
3. Explicitly call `Security.addProvider(new Smartcardio());`. Then call `TerminalFactory.getInstance("PC/SC", null, Smartcardio.PROVIDER_NAME);`

Once you have a TerminalFactory, you call `cardTerminals = factory.terminals(); cardTerminals.list()`.

Caveats
---
This library is not ready for use in production. It’s incomplete; a few methods might still be no-ops. I have only tested it a very small amount.

This library requires JNA to talk to the native libraries (winscard.dll, libpcsc.so, or PCSC). You can’t use this library if you are writing an applet or are otherwise using a security manager.

License
---
This code is released under [CC0](http://creativecommons.org/publicdomain/zero/1.0/legalcode); it is a “universal donor” in the hope that others can find it useful and contribute back.