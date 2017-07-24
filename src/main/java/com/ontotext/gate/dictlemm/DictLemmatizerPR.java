/* 
 * Copyright (C) 2015-2016 The University of Sheffield.
 *
 * This file is part of gateplugin-Lemmatizer
 * (see https://github.com/GateNLP/gateplugin-Lemmatizer)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software. If not, see <http://www.gnu.org/licenses/>.
 */

package com.ontotext.gate.dictlemm;

import gate.*;
import gate.creole.ResourceData;
import gate.creole.metadata.CreoleParameter;
import gate.creole.metadata.CreoleResource;
import gate.creole.metadata.Optional;
import gate.creole.metadata.RunTime;
import gate.util.GateRuntimeException;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A PR to find lemmata for words.
 *
 * @author Johann Petrak <johann.petrak@gmail.com>
 * @author Achmet Aker
 */
@CreoleResource(
		name = "DictLemmatizer",
		helpURL = "https://github.com/GateNLP/gateplugin-dict-lemmatizer/wiki/Lemmatizer",
		comment = "Find the lemmata of words."
)
public class DictLemmatizerPR extends AbstractDocumentProcessor {
	private static final Logger LOGGER = Logger.getLogger(DictLemmatizerPR.class);

	private static final long serialVersionUID = 1L;

	protected String inputASName;
	protected String inputType;
	protected String containingType;
	protected String textFeature;
	private String posFeature;
	private String lemmaFeature;
	private String languageCode;

	@RunTime
	@Optional
	@CreoleParameter(comment = "Input annotation set", defaultValue = "")
	public void setInputAnnotationSet(String ias) {
		inputASName = ias;
	}

	public String getInputAnnotationSet() {
		return inputASName;
	}

	@RunTime
	@CreoleParameter(comment = "The input annotation type", defaultValue = "Token")
	public void setInputAnnotationType(String val) {
		this.inputType = val;
	}

	public String getInputAnnotationType() {
		return inputType;
	}

	@RunTime
	@Optional
	@CreoleParameter(comment = "The optional containing annotation set type", defaultValue = "")
	public void setContainingAnnotationType(String val) {
		this.containingType = val;
	}

	public String getContainingAnnotationType() {
		return containingType;
	}

	@RunTime
	@Optional
	@CreoleParameter(comment = "The feature from the input annotation to use as word string, if left blank the document text", defaultValue = "string")
	public void setTextFeature(String val) {
		this.textFeature = val;
	}

	public String getTextFeature() {
		return textFeature;
	}

	@RunTime
	@Optional
	@CreoleParameter(comment = "The feature that contains the POS tag", defaultValue = "category")
	public void setPosFeature(String val) {
		posFeature = val;
	}

	public String getPosFeature() {
		return posFeature;
	}

	@RunTime
	@CreoleParameter(comment = "The name of the feature that should contain the lemma, if any.", defaultValue = "lemma")
	public void setLemmaFeature(String val) {
		lemmaFeature = val;
	}

	public String getLemmaFeature() {
		return lemmaFeature;
	}

	@RunTime
	@CreoleParameter(comment = "The language code to use, e.g. en, de, fr", defaultValue = "en")
	public void setLanguageCode(String val) {
		languageCode = val;
	}

	public String getLanguageCode() {
		return languageCode;
	}

	////////////////////// FIELDS
	Map<String, String> nounDic;
	Map<String, String> adjDic;
	Map<String, String> advDic;
	Map<String, String> verbDic;
	Map<String, String> detDic;
	Map<String, String> pronDic;
	Map<String, String> partDic;
	Map<String, String> adpDic;

	String textFeatureToUse = "string";
	String posFeatureToUse = "category";
	String lemmaFeatureToUse = "lemma";

	String loadedDicts = "";
	String loadedFst = "";

	int nrTokens = 0;
	int nrErrors = 0;
	int nrHfst = 0;
	int nrListLookups = 0;
	int nrListLookupsNotFound = 0;

	HfstLemmatizer hfstLemmatizer = null;  // if null we do not have a FST

	// If this is true, the Hfst will always be suppressed.
	// This can only be set (for debugging) by setting to propery
	// gateplugin-Lemmatizer.noHfst to something other than the string "false";
	private boolean noHfst = false;

	////////////////////// PROCESSING
	@Override
	protected Document process(Document document) {
		AnnotationSet inputAS;
		if (inputASName == null || inputASName.isEmpty()) inputAS = document.getAnnotations();
		else inputAS = document.getAnnotations(inputASName);

		AnnotationSet inputAnns;
		if (inputType == null || inputType.isEmpty()) throw new GateRuntimeException("Input annotation type must not be empty!");
		inputAnns = inputAS.get(inputType);

		AnnotationSet containingAnns = null;
		if (containingType != null && !containingType.isEmpty()) containingAnns = inputAS.get(containingType);

		fireStatusChanged("running on " + document.getName() + "...");

		// do it for each containing annotation
		if (containingAnns == null) doIt(document, inputAnns);
		else for (Annotation containingAnn : containingAnns) {
			doIt(document, Utils.getContainedAnnotations(inputAnns, containingAnn));
		}

		fireProcessFinished();
		fireStatusChanged("processing complete!");
		return document;
	}

	private void doIt(Document doc, AnnotationSet anns) {
		for (Annotation token : anns) {
			FeatureMap fm = token.getFeatures();
			String pos = (String) fm.get(posFeatureToUse);
			if (pos != null && !pos.trim().isEmpty()) lemmatize(token, fm, pos);
		}
	}

	private void lemmatize(Annotation token, FeatureMap fm, String pos) {
		String lemmatizeStatus = "";   // an indication how we did the lemmatization for this token
		nrTokens += 1;
		String tokenString;
		if (textFeatureToUse == null) tokenString = Utils.cleanStringFor(document, token);
		else tokenString = (String) fm.get(textFeatureToUse);

		String kind = ((String) fm.get("kind")).toLowerCase();
		String lemma = null;  // as long as the lemma is null we can still try to find one ...
		if (kind.equals("number")) {
			lemma = tokenString;
			lemmatizeStatus = "number";
		} else if (kind.equals("punct")) {
			lemma = tokenString;
			lemmatizeStatus = "punct";
		} else {

			// https://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
			if (pos.startsWith("JJ")) {
				lemma = adjDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "ADJ";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
			} else if (pos.startsWith("IN")) {
				lemma = adpDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "ADP";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
			} else if (pos.startsWith("RB")) {
				lemma = advDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "ADV";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
				// MISSING: AUX, CCONJ
			} else if (pos.startsWith("DT")) {
				lemma = detDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "DET";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
				// MISSSING: INTJ
			} else if (pos.startsWith("NN")) {
				lemma = nounDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "NOUN";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
				// MISSING: NUM
			} else if (pos.startsWith("RP")) {
				lemma = partDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "PART";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
			} else if (pos.startsWith("PR")) {
				lemma = pronDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "PRON";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
				// MISSING: PROPN, PUNCT, SCONJ, SYM
			} else if (pos.startsWith("VB")) {
				lemma = verbDic.get(tokenString.toLowerCase());
				lemmatizeStatus = "VERB";
				nrListLookups += 1;
				if (lemma == null) nrListLookupsNotFound += 1;
				// MISSING: X
			} else {
				lemmatizeStatus = "UNHANDLEDPOS-" + pos;
			}

			if (lemma == null) lemmatizeStatus += "-NOTFOUND";
			else lemmatizeStatus += "-FOUND";

			// TODO: replace with indicator of if we have a FST from the init phase
			if (lemma == null && hfstLemmatizer != null && !noHfst) {
				try {
					nrHfst += 1;
					lemma = hfstLemmatizer.getLemma(tokenString, pos);
					if (lemma != null && !lemma.isEmpty()) {
						lemmatizeStatus += "-HFST_HAVE";
					} else {
						lemma = tokenString;
						lemmatizeStatus += "-HFST_EMPTY";
					}
				} catch (Exception ex) {
					LOGGER.error("Exception for " + tokenString + ": " + ex.getClass() + ", " + ex.getMessage(), ex);
					lemma = tokenString;
					lemmatizeStatus += "-HFST_ERROR";
					nrErrors += 1;
				}
			}

			// NOTE: this will only happen if we did not find a lemma in the dictionary and
			// HFST was not used for some reason
			if (lemma == null) {
				lemma = tokenString;
				lemmatizeStatus += "-NOHFST";
			}
		}

		fm.put(lemmaFeatureToUse, lemma);
		fm.put("lemmatizer.status", lemmatizeStatus);
	}

	@Override
	protected void beforeFirstDocument(Controller ctrl) {
		nrTokens = 0;
		nrErrors = 0;
		nrHfst = 0;
		nrListLookups = 0;
		nrListLookupsNotFound = 0;

		if (posFeature == null || posFeature.trim().isEmpty()) posFeatureToUse = "category";
		else posFeatureToUse = posFeature;

		if (textFeature == null || textFeature.trim().isEmpty()) textFeatureToUse = null;
		else textFeatureToUse = textFeature;

		if (lemmaFeature == null || lemmaFeature.trim().isEmpty()) lemmaFeatureToUse = "lemma";
		else lemmaFeatureToUse = lemmaFeature;

		ResourceData myResourceData = Gate.getCreoleRegister().get(this.getClass().getName());
		java.net.URL creoleXml = myResourceData.getXmlFileUrl();
		File pluginDir = gate.util.Files.fileFromURL(creoleXml).getParentFile();
		File resourcesDir = new File(pluginDir, "resources");
		File dictDir = new File(new File(resourcesDir, "dictionaries"), languageCode);
		if (!loadedDicts.equals(languageCode)) {
			// if there are no dictionaries or the use of dictionaries has been disabled,
			// create empty ones, and only the hfst is used.
			// Otherwise load the dictionaries, at least the ones which are there
			boolean noLists = false;
			if (!dictDir.exists()) {
				LOGGER.debug("List directory " + dictDir + " for language " + languageCode + " does not exist, not using lists.");
				noLists = true;
			}
			String noListsProp = System.getProperty("gateplugin-Lemmatizer.noLists");
			if (noListsProp != null && !noListsProp.toLowerCase().equals("false")) {
				LOGGER.debug("gateplugin-Lemmatizer.noLists is set, not using lists");
				noLists = true;
			}
			String noListsEnv = System.getenv("GATEPLUGIN_LEMMATIZER_NOLISTS");
			if (noListsEnv != null && !noListsEnv.toLowerCase().equals("false")) {
				LOGGER.debug("GATEPLUGIN_LEMMATIZER_NOLISTS is set, not using lists");
				noLists = true;
			}
			if (noLists) {
				adjDic = emptyDictionary();
				adpDic = emptyDictionary();
				advDic = emptyDictionary();
				detDic = emptyDictionary();
				nounDic = emptyDictionary();
				pronDic = emptyDictionary();
				verbDic = emptyDictionary();
				partDic = emptyDictionary();
			} else {
				LOGGER.debug("loading dictionaries for " + languageCode);
				adjDic = loadDictionary(new File(dictDir, "ADJ-Dict.txt.gz"));
				adpDic = loadDictionary(new File(dictDir, "ADP-Dict.txt.gz"));
				advDic = loadDictionary(new File(dictDir, "ADV-Dict.txt.gz"));
				detDic = loadDictionary(new File(dictDir, "DET-Dict.txt.gz"));
				nounDic = loadDictionary(new File(dictDir, "NOUN-Dict.txt.gz"));
				partDic = loadDictionary(new File(dictDir, "PART-Dict.txt.gz"));
				pronDic = loadDictionary(new File(dictDir, "PRON-Dict.txt.gz"));
				verbDic = loadDictionary(new File(dictDir, "VERB-Dict.txt.gz"));
				LOGGER.debug("dictionaries loaded");
			}
			loadedDicts = languageCode;
		}

		// Load the hfst lemmatizer if it exists for the language, otherwise
		// the hfstLemmatizer variable remains null
		File lemmatizerDir = new File(resourcesDir, "lemmaModels");
		File lemmatizerFile = new File(lemmatizerDir, languageCode + ".hfst.ol.gz");
		if (lemmatizerFile.exists()) {
			if (!loadedFst.equals(languageCode)) {
				try {
					noHfst = false;
					String noHfstProp = System.getProperty("gateplugin-Lemmatizer.noHfst");
					if (noHfstProp != null && !noHfstProp.toLowerCase().equals("false")) {
						LOGGER.debug("gateplugin-Lemmatizer.noHfst is set, not using  HFST");
						noHfst = true;
					}
					String noHfstEnv = System.getenv("GATEPLUGIN_LEMMATIZER_NOHFST");
					if (noHfstEnv != null && !noHfstEnv.toLowerCase().equals("false")) {
						LOGGER.debug("GATEPLUGIN_LEMMATIZER_NOHFST is set, not using  HFST");
						noHfst = true;
					}
					if (!noHfst) {
						LOGGER.debug("loading HFST model for " + languageCode);
						hfstLemmatizer = HfstLemmatizer.load(lemmatizerFile, languageCode);
						LOGGER.debug("HFST model loaded");
					}
				} catch (Exception ex) {
					throw new GateRuntimeException("Could not load lemmatization transducer " + lemmatizerFile, ex);
				}
				loadedFst = languageCode;
			}
		} else {
			hfstLemmatizer = null;
		}

	}

	@Override
	protected void afterLastDocument(Controller ctrl, Throwable t) {
		LOGGER.debug("Tokens processed:                   " + nrTokens);
		LOGGER.debug("Tokens for which HFST was invoked:  " + nrHfst);
		LOGGER.debug("Tokens for which HFST had errors:   " + nrErrors);
		LOGGER.debug("Tokens for which Lists looked up:   " + nrListLookups);
		LOGGER.debug("Tokens for which not found in List: " + nrListLookupsNotFound);
	}

	@Override
	protected void finishedNoDocument(Controller ctrl, Throwable t) {}

	public static Map<String, String> emptyDictionary() {
		return new HashMap<>();
	}

	public static Map<String, String> loadDictionary(File dictFile) {
		Map<String, String> map = new HashMap<>();
		if (!dictFile.exists()) {
			LOGGER.debug("dictionary file does not exist:  " + dictFile.getAbsolutePath());
			return map;
		}

		try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(dictFile)), "UTF-8"))) {
			String str;
			while ((str = in.readLine()) != null) {
				if (!"".equals(str.trim())) {
					String values[] = str.split("===");
					if (values.length == 2) {
						String vals[] = values[1].split(";");
						for (String val : vals) {
							map.put(val.toLowerCase(), values[0].trim());
						}
					}
				}
			}

			return map;
		} catch (Exception ex) {
			throw new GateRuntimeException("Could not read dictionary " + dictFile.getAbsolutePath(), ex);
		}
	}
}
