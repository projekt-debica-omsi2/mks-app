package pl.debica.mks.app;

import org.w3c.dom.Document;

/** Prosty test prawdziwej klasy GpsXml, bez potrzeby instalowania Android SDK. */
public final class GpsXmlSmokeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Document ping = GpsXml.parse("<?xml version=\"1.0\" encoding=\"utf-8\"?><int xmlns=\"http://schemas.microsoft.com/2003/10/Serialization/\">42</int>");
        check("42".equals(ping.getDocumentElement().getTextContent().trim()), "PingService XML");
        Document buses = GpsXml.parse("<?xml version=\"1.0\"?><Vehicles><V x=\"21.411\" y=\"50.05\" nr=\"3\"/></Vehicles>");
        check(buses.getElementsByTagName("V").getLength() == 1, "GetVehicles XML");
        boolean blocked = false;
        try {
            GpsXml.parse("<!DOCTYPE x [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]><x>&secret;</x>");
        } catch (Exception expected) { blocked = true; }
        check(blocked, "DTD must be refused");
        System.out.println("OK: PingService, GetVehicles, blokada DTD — parser XML");
    }
}
