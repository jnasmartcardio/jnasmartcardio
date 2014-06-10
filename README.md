jnasmartcardio
===
(Previously known as jna2pcsc.) A re-implementation of the [`javax.smartcardio` API](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/). It allows you to communicate to a smart card (at the APDU level) from within Java.

In case you aren’t familiar with the technology, a smart card is a tiny CPU that fits inside a piece of plastic the size of a credit card. For example, you can buy the MyEID for about $15 to securely store and create signatures from a 2048-bit RSA private key. In order to use a smart card, you also have to buy a $15 USB smart card reader. Once you plug in the smart card reader, you use the winscard library built-in to Windows or the pcsclite library on OS X and Linux to communicate with the card. This library is an adapter that converts the native winscard API to the friendly `javax.smartcardio` interfaces.

Alternatives
---
The JRE already comes with implementations of `javax.smartcardio`. What’s wrong with it? If you are already using the smartcardio API, there are a couple reasons you might consider switching to a JNA solution:

* The [default smartcardio library in JRE 1.7 on 64-bit OS X is compiled incorrectly](http://mail.openjdk.java.net/pipermail/security-dev/2013-March/006913.html). In particular, `Terminal.isCardPresent()` always returns false, `Terminals.list()` occasionally causes SIGSEGV, and `Terminal.waitForCard(boolean, long)` and `Terminals.waitForChange(long)` don’t wait.
* The default smartcardio library only calls `SCardEstablishContext` once. If the daemon isn’t up yet, then your process will never be able to connect to it again. This is a big problem because in Windows 8, OS X, and new versions of pcscd, the daemon is not started until a reader is plugged in, and it quits when there are no more readers.
* It’s easier to fix bugs in this project than it is to fix bugs in the libraries that are bundled with the JRE.

Another implementation of the smartcardio API is [intarsys/smartcard-io](https://github.com/intarsys/smartcard-io), which is much more mature than this project. Please consider it. You might choose jnasmartcardio instead because:

* jnasmartcardio is much smaller and has fewer dependencies than smartcard-io.

Installation
---

Download the most recent published release from the Maven Central Repository. If you are using maven, you simply add this dependency to your own project’s pom.xml:

	<dependency>
		<groupId>io.github.jnasmartcardio</groupId>
		<artifactId>jnasmartcardio</artifactId>
		<version>0.2.1</version>
	</dependency>

To build from source, run the following command to compile, jar, and install to your local Maven repository. Don’t forget to also modify your own project’s pom.xml to depend on the same SNAPSHOT version. You may need to learn the [Maven version arcana](http://docs.codehaus.org/display/MAVEN/Dependency+Mediation+and+Conflict+Resolution).

    mvn install

Once you have jnasmartcardio in your classpath, there are 3 ways to use this smartcard provider instead of the one that is bundled with JRE:

1. Modify &lt;java_home&gt;/jre/lib/security/java.security; replace `security.provider.9=sun.security.smartcardio.SunPCSC` with `security.provider.9=jnasmartcardio.Smartcardio`. Then use `TerminalFactory.getDefault()`.
2. Create a file override.java.security, then add system property -Djava.security.properties=override.java.security. This should be a file that contains a line like the above. But make sure that you override the same numbered line as the existing SunPCSC in your JRE; otherwise, you may disable some other factory too! Then use `TerminalFactory.getDefault()`
3. Explicitly reference the Smartcardio class at compile time. There are a few variations of this:
    * `Security.addProvider(new Smartcardio());` `TerminalFactory.getInstance("PC/SC", null, Smartcardio.PROVIDER_NAME);`
    * `Security.insertProviderAt(new Smartcardio(), 1);` `TerminalFactory.getInstance("PC/SC", null);`
    * `TerminalFactory.getInstance("PC/SC", null, new Smartcardio());`

Once you have a TerminalFactory, you call `cardTerminals = factory.terminals();`; see [javax.smartcardio API javadoc](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/package-summary.html).

Changelog
---
See [CHANGES.md](CHANGES.md).

Caveats
---
This library requires JNA to talk to the native libraries (winscard.dll, libpcsc.so, or PCSC). You can’t use this library if you are writing an applet or are otherwise using a security manager.

Differences from JDK
---
Some things to keep in mind which are different from JDK:

Generally, all methods will throw a JnaPCSCException if the daemon/service is off (when there are no readers). On Windows 8, the service is stopped immediately when there are no more readers.

### TerminalFactory

[TerminalFactory.terminals()](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/TerminalFactory.html#terminals%28%29) will (re-)establish connection with the PCSC daemon/service. If the service is not running, terminals() will throw an unchecked exception EstablishContextException.

### JnaCardTerminals

JnaCardTerminals owns the SCardContext native handle, and you should call cardTerminals.close() to clean up. Unfortunately, close() does not exist on the base class.

To make the implementation simpler, the caller must be able to handle spurious wakeups when calling [waitForChange(long)](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/CardTerminals.html#waitForChange%28long%29). In other words, `list(State.CARD_REMOVAL)` and `list(State.CARD_INSERTION)` might both be empty lists after waitForChange returns.

As well as waking up when a card is inserted/removed, waitForChange will also wake up when a card reader is plugged in/unplugged. However, in Windows 8, when all readers are unplugged the service will immediately exit, so waitForChange will throw an exception instead of returning.

### JnaCard

[beginExclusive()](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/Card.html#beginExclusive%28%29) simply calls SCardBeginTransaction. It does not use thread-local storage, as Sun does.

### JnaCardChannel

[transmit(CommandAPDU command)](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/CardChannel.html#transmit%28javax.smartcardio.CommandAPDU%29) currently has a response limit of 8192 bytes.

Transmit does the following automatically:

* Sets the channel number in the class byte (CLA)
* If T=0 and Lc ≠ 0 and Le ≠ 0, then the Le byte is removed as required.
* If sw=61xx, then Get Response is automatically sent until the entire response is received.
* If sw=6cxx, then the request is re-sent with the right Le byte.

However, keep in mind:

* If T=0, then you must not send a Command APDU with extended Lc/Le. User is responsible for using Envelope commands if needed to turn T=1 commands into T=0 commands.
* You may perform your own command chaining (e.g. if command is too long to fit in one Command APDU). You must put the command chaining bits in the correct position within the CLA byte, depending on the channel number.
* If you are using secure messaging, you must put the secure messaging bits in the right position within the CLA byte, depending on the channel number.

License
---
This code is released under [CC0](http://creativecommons.org/publicdomain/zero/1.0/legalcode); it is a “universal donor” in the hope that others can find it useful and contribute back.