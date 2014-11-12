jnasmartcardio
===
(Previously known as jna2pcsc.) A re-implementation of the [`javax.smartcardio` API](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/). It allows you to communicate to a smart card (at the APDU level) from within Java.

This library allows you to transmit and receive application protocol data units (APDUs) specified by ISO/IEC 7816-3 to a smart card.

This java library is built on top of the WinSCard native library that comes with the operating system (or libpcsclite1 installed on Linux), which in turn communicates to the myriad USB smart card readers, contactless card readers, and dongles.

Protocols built on top of APDUs include PKCS#15 public key authentication, EMV credit/debit transaction, GSM SIM cellular subscriber information, CAC U.S. military identification, Mifare Classic or DESfire transit payment, and any number of custom protocols.

Alternatives
---

First, if you are using smart cards for authentication and it comes with a PKCS#11 native library (or is supported by opensc-pkcs11), you should probably use the SunPKCS11 KeyStore provider instead of implementing the PKCS#15 client protocol yourself.

Once you have decided on APDU communication, you may wonder why this library exists, given that the JRE already comes with an implementation of `javax.smartcardio`. What’s wrong with it? There are a couple reasons you might consider switching to a JNA solution:

* The [default smartcardio library in JRE 7 and JRE 8 on 64-bit OS X was compiled incorrectly](http://ludovicrousseau.blogspot.com/2013/03/oracle-javaxsmartcardio-failures.html); [bug 7195480](http://bugs.java.com/bugdatabase/view_bug.do?bug_id=7195480). In particular, `Terminal.isCardPresent()` always returns false, `Terminals.list()` occasionally causes SIGSEGV, and `Terminal.waitForCard(boolean, long)` and `Terminals.waitForChange(long)` don’t wait. Ivan Gerasimov (igerasim) [fixed `waitForCard`](http://mail.openjdk.java.net/pipermail/security-dev/2014-May/010498.html), [fixed `list` and `isCardPresent`](http://mail.openjdk.java.net/pipermail/security-dev/2014-May/010515.html), and [fixed Card.openLogicalChannel](http://mail.openjdk.java.net/pipermail/security-dev/2014-June/010695.html) for JRE 7u80, 8u20, and 9.
* The default smartcardio library only calls `SCardEstablishContext` once. If the daemon isn’t up yet, then your process will never be able to connect to it again. This is a big problem because in Windows 8, OS X, and new versions of pcscd, the daemon is not started until a reader is plugged in, and it quits when there are no more readers.
* It’s easier to fix bugs in this project than it is to fix bugs in the libraries that are bundled with the JRE. Anybody can create and comment on issues.

Another implementation of the smartcardio API is [intarsys/smartcard-io](https://github.com/intarsys/smartcard-io), which is much more mature than this project. Please consider it. You might choose jnasmartcardio instead because:

* jnasmartcardio is much smaller and has fewer dependencies than smartcard-io.

Installation
---

Requires JDK 1.6 or above.

Download the most recent published release from the Maven Central Repository. If you are using maven, you simply add this dependency to your own project’s pom.xml:

	<dependency>
		<groupId>io.github.jnasmartcardio</groupId>
		<artifactId>jnasmartcardio</artifactId>
		<version>0.2.4</version>
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

Differences from JRE
---
Some things to keep in mind which are different from JRE:

Generally, all methods will throw a JnaPCSCException if the daemon/service is off (when there are no readers). On Windows 8, the service is stopped immediately when there are no more readers.

### TerminalFactory

[TerminalFactory.terminals()](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/TerminalFactory.html#terminals%28%29) will (re-)establish connection with the PCSC daemon/service. If the service is not running, terminals() will throw an unchecked exception EstablishContextException.

### JnaCardTerminals

JnaCardTerminals owns the SCardContext native handle, and you should call cardTerminals.close() to clean up. Unfortunately, close() does not exist on the base class, so this library also closes it in its finalizer.

To make the implementation simpler, the caller must be able to handle spurious wakeups when calling [waitForChange(long)](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/CardTerminals.html#waitForChange%28long%29). In other words, `list(State.CARD_REMOVAL)` and `list(State.CARD_INSERTION)` might both be empty lists after waitForChange returns.

As well as waking up when a card is inserted/removed, waitForChange will also wake up when a card reader is plugged in/unplugged. However, in Windows 8, when all readers are unplugged the service will immediately exit, so waitForChange will throw an exception instead of returning.

### JnaCardTerminal

[connect(String protocol)](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/CardTerminal.html#connect%28java.lang.String%29) supports exactly the same connection modes as Sun does: T=0, T=1, T=*, and T=DIRECT (T=CL is mentioned in the smartcardio documentation but is not accepted). Unlike Sun, it does not return the same connection when you connect twice.

### JnaCard

[beginExclusive()](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/Card.html#beginExclusive%28%29) simply calls SCardBeginTransaction. It does not use thread-local storage, as Sun does.

[disconnect(boolean reset)](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/javax/smartcardio/Card.html#disconnect%28boolean%29) did the opposite in Sun’s implementation, which suffered [bug 7047033](http://bugs.java.com/bugdatabase/view_bug.do?bug_id=7047033). Ivan Gerasim of Oracle [fixed their implementation](http://mail.openjdk.java.net/pipermail/security-dev/2014-May/010554.html) to match mine in JRE 7u80, 8u20, and 9, although [the old behavior can be obtained by -Djdk.smartcard.invertReset=true in JRE 8](http://mail.openjdk.java.net/pipermail/security-dev/2014-July/010725.html).

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