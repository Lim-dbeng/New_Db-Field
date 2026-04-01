package com.newdbfield.fac;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class SqlRepository {
	private final Map<String, String> sqls = new HashMap<>();

	public SqlRepository(InputStream xml) {
		if (xml != null) {
			try {
				Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
				NodeList list = doc.getElementsByTagName("sql");
				for (int i = 0; i < list.getLength(); i++) {
					Node n = list.item(i);
					String id = n.getAttributes().getNamedItem("id").getNodeValue();
					String text = n.getTextContent();
					sqls.put(id, text.trim());
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public String get(String id) {
		String s = sqls.get(id);
		if (s == null) throw new IllegalArgumentException("SQL not found: " + id);
		return s;
	}
}


