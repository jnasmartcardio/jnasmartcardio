jnasmartcardio
===
(Previously known as jna2pcsc.) A re-implementation of the [`javax.smartcardio` API](http://docs.oracle.com/javase/7/docs/jre/api/security/smartcardio/spec/). It allows you to communicate to a smart card (at the APDU level) from within Java.

In case you aren’t familiar with the technology, a smart card is a tiny CPU that fits inside a piece of plastic the size of a credit card. For example, you can buy the MyEID for about $15 to securely store and create signatures from a 2048-bit RSA private key. In order to use a smart card, you also have to buy a $15 USB smart card reader. Once you plug in the smart card reader, you use the winscard library built-in to Windows or the pcsclite library on OS X and Linux to communicate with the card. This library is an adapter that converts the native winscard API to the friendly `javax.smartcardio` interfaces.

Alternatives
---
The JRE already comes with implementations of `javax.smartcardio`. What’s wrong with it? If you are already using the smartcardio API, there are a couple reasons you might consider switching to a JNA solution:

* The [default smartcardio library in JRE 1.7 on 64-bit OS X is compiled incorrectly](http://mail.openjdk.java.net/pipermail/security-dev/2013-March/006913.html). In particular, `Terminal.isCardPresent()` always returns false, `Terminals.list()` occasionally causes SIGSEGV, and `Terminal.waitForCard(boolean, long)` and `Terminals.waitForChange(long)` don’t work.
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
		<version>0.2.0</version>
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

License
---
This code is released under [CC0](http://creativecommons.org/publicdomain/zero/1.0/legalcode); it is a “universal donor” in the hope that others can find it useful and contribute back.