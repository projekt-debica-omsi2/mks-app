package pl.debica.mks.app;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Parser odpowiedzi API MKS zgodny z Androidem.
 *
 * Nie używa nieobsługiwanych na części telefonów funkcji SAX/Xerces
 * (np. disallow-doctype-decl). DTD i deklaracje encji odrzucamy przed
 * parsowaniem, a EntityResolver blokuje wszelkie zewnętrzne zasoby.
 */
final class GpsXml {
    private GpsXml() { }

    static Document parse(String xml) throws Exception {
        if (xml == null || xml.trim().isEmpty())
            throw new Exception("Serwer MKS zwrócił puste dane XML");

        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
            throw new Exception("Odpowiedź XML zawiera niedozwoloną deklarację DTD lub encji");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
