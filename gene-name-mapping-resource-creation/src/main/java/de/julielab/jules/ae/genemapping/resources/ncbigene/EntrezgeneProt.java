package de.julielab.jules.ae.genemapping.resources.ncbigene;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the Entrezgene_prot element of the gene XML format.
 * 
 * @author faessler
 *
 */
public class EntrezgeneProt {
	public List<String> protrefName;
	public String protrefDesc;

	public void addProtrefName(String name) {
		if (null == protrefName)
			protrefName = new ArrayList<>();
		protrefName.add(name);
	}

	@Override
	public String toString() {
		return "EntrezgeneProt [protrefName=" + protrefName + ", protrefDesc=" + protrefDesc + "]";
	}

}