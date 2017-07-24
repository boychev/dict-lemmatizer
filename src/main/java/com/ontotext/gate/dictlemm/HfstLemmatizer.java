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

import fi.seco.hfst.*;
import fi.seco.hfst.Transducer.Result;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * A class representing the HFST lemmatizer transducer.
 *
 * @author Ahmet Aker
 * @author Johann Petrak
 */
@SuppressWarnings("Duplicates")
public class HfstLemmatizer {
	private Transducer transducer = null;
	private String langCode = null;

	protected HfstLemmatizer(Transducer t, String langCode) {
		transducer = t;
		this.langCode = langCode;
	}

	public static HfstLemmatizer load(File resourceFile, String langCode) throws Exception {
		Transducer tr;
		// TODO: the TransducerHeader and WeightedTransducer etc classes cannot
		// handle InputStream they need FileInputStream so it is not possible
		// to do on-the-fly compression of the model files. Would need to
		// change the library or find a version that can do this.
		FileInputStream ifs = new FileInputStream(resourceFile);
		GZIPInputStream gis = new GZIPInputStream(ifs);
		TransducerStream ts = new TransducerStream(new DataInputStream(gis));
		TransducerHeader h = new TransducerHeader(ts);
		TransducerAlphabet a = new TransducerAlphabet(ts, h.getSymbolCount());
		if (h.isWeighted()) tr = new WeightedTransducer(ts, h, a);
		else tr = new UnweightedTransducer(ts, h, a);
		return new HfstLemmatizer(tr, langCode);
	}

	public String getLemma(String aWord, String aPOSType) throws Exception {
		List<Result> analyses;
		// NOTE: this will not catch any exceptions so we can catch them in the caller
		// and do some debugging
		analyses = transducer.analyze(aWord);
		for (Result analysisResult : analyses) {
			// TODO: this is incorrect, we need to change this
			String analysis = String.join("", analysisResult.getSymbols());
			if ("en".equalsIgnoreCase(langCode)) {
				String grammar = "NONE";
				String grammarCheck = "NONE";
				if (aPOSType.startsWith("NN")) {
					grammar = "\\[N\\]\\+N.*";
					grammarCheck = "[N]+N";
				} else if (aPOSType.startsWith("VB")) {
					grammar = "\\[V\\]\\+V.*";
					grammarCheck = "[V]+V";
				} else if (aPOSType.startsWith("JJ")) {
					grammar = "\\[ADJ\\]\\+ADJ.*";
					grammarCheck = "[ADJ]+ADJ";
				} else if (aPOSType.startsWith("RB")) {
					grammar = "\\[ADV\\]\\+ADV.*";
					grammarCheck = "[ADV]+ADV";
				}

				if (analysis.contains(grammarCheck)) {
					String lemma = analysis.replaceAll(grammar, "");
					if ((lemma.contains("+") && !lemma.contains("-")) && (aWord.contains("-") && !aWord.contains("+"))) lemma = lemma.replaceAll("\\+", "-");
					if (lemma.contains("+") && !aWord.contains("+")) lemma = lemma.replaceAll("\\+", "");
					return lemma.toLowerCase();
				}

			} else if ("de".equalsIgnoreCase(langCode)) {
				String grammar = "NONE";
				String grammar2 = ">";
				String grammarCheck = "NONE";
				if (aPOSType.startsWith("NN")) {
					grammar = "<\\+NN>.*";
					grammarCheck = "<+NN>";
				} else if (aPOSType.startsWith("VB")) {
					grammar = "<\\+V>.*";
					grammarCheck = "<+V>";
				} else if (aPOSType.startsWith("JJ")) {
					grammar = "<\\+ADJ>.*";
					grammarCheck = "<+ADJ>";
				} else if (aPOSType.startsWith("RB")) {
					grammar = "<\\+ADV>.*";
					grammarCheck = "<+ADV>";
				} else if (aPOSType.startsWith("CC")) {
					grammar = "<\\+KONJ>.*";
					grammarCheck = "<+KONJ>";
				}

				if (analysis.contains(grammarCheck)) {
					String remaining = analysis.replaceAll(grammar, "");
					String vals[] = remaining.split(grammar2);
					StringBuilder builder = new StringBuilder();
					String suffix = "";
					for (int i = 0; i < vals.length - 1; i++) {
						String val = vals[i];
						if (!val.startsWith("<CAP")) {
							val = val.replaceAll("<.*", "");
							builder.append(val.toLowerCase());
						}
					}
					String lastWord = vals[vals.length - 1].replaceAll("<.*", "");
					if (lastWord.endsWith("<SUFF")) suffix = lastWord.toLowerCase();

					String result = null;
					if (aWord.toLowerCase().equals(builder.toString())) {
						return aWord.toLowerCase();

					} else {
						// TODO: apparently the lastWord can be the empty string here sometimes!
						if (lastWord.equals("")) return null;
						String lastChar = lastWord.substring(lastWord.length() - 1, lastWord.length());
						String local = builder.toString() + lastChar;
						if (local.equalsIgnoreCase(aWord)) return local;

						// TODO: this sometimes tries to take the substring using index -1
						// TODO!! BUG!!!
						// So we wrapped the if around it but not sure if this is the correct thing to do!!
						if (lastWord.length() > 2) {
							String last2Char = lastWord.substring(lastWord.length() - 2, lastWord.length());
							local = builder.toString() + last2Char;
						}

						if (local.equalsIgnoreCase(aWord)) return local;
					}

					if (aWord.toLowerCase().startsWith(builder.toString()) && !builder.toString().trim().equals("")) {
						String wordRemaining = aWord.toLowerCase().replaceAll(builder.toString(), "");
						wordRemaining = wordRemaining.replaceAll(lastWord.toLowerCase(), "");
						if (!wordRemaining.trim().equals("") && wordRemaining.trim().length() <= 2) {

							if (!suffix.equals("")) {
								result = builder.append(wordRemaining).toString();
							} else {
								String local = builder.toString() + lastWord.toLowerCase();
								if (aWord.toLowerCase().startsWith(local)) result = local;
								else result = builder.append(wordRemaining).append(lastWord.toLowerCase()).toString();
							}

						} else {
							result = builder.append(lastWord.toLowerCase()).toString();
						}

					} else if (builder.toString().trim().equals("")) {
						result = builder.append(vals[vals.length - 1].toLowerCase()).toString().replaceAll("<.*", "");
					}

					if (result != null) result = result.replaceAll("\\{", "").replaceAll("\\}", "");
					return result;
				}

			} else if ("it".equalsIgnoreCase(langCode)) {
				String grammar = "NONE";
				String grammarCheck = "NONE";
				if (aPOSType.startsWith("NN")) {
					grammar = "#NOUN.*";
					grammarCheck = "#NOUN";
				} else if (aPOSType.startsWith("VB")) {
					grammar = "#VER.*";
					grammarCheck = "#VER";
				} else if (aPOSType.startsWith("JJ")) {
					grammar = "#ADJ.*";
					grammarCheck = "#ADJ";
				} else if (aPOSType.startsWith("RB")) {
					grammar = "#ADV.*";
					grammarCheck = "#ADV";
				} else if (aPOSType.startsWith("CC")) {
					grammar = "#CON.*";
					grammarCheck = "#CON";
				}

				if (analysis.contains(grammarCheck)) {
					String lemma = analysis.replaceAll(grammar, "");
					if ((lemma.contains("+") && !lemma.contains("-")) && (aWord.contains("-") && !aWord.contains("+"))) lemma = lemma.replaceAll("\\+", "-");
					if (lemma.contains("+") && !aWord.contains("+")) lemma = lemma.replaceAll("\\+", "");
					return lemma.toLowerCase();
				}

			} else if ("fr".equalsIgnoreCase(langCode)) {
				String grammar = "NONE";
				String grammarCheck = "NONE";
				if (aPOSType.startsWith("NN")) {
					grammar = "\\+commonNoun.*";
					grammarCheck = "+commonNoun";
				} else if (aPOSType.startsWith("VB")) {
					grammar = "\\+verb+.*";
					grammarCheck = "+verb+";
				} else if (aPOSType.startsWith("JJ")) {
					grammar = "\\+adjective.*";
					grammarCheck = "+adjective";
				} else if (aPOSType.startsWith("RB")) {
					grammar = "\\+adverb.*";
					grammarCheck = "+adverb";
				} else if (aPOSType.startsWith("PR") || aPOSType.startsWith("CC")) {
					grammar = "\\+functionWord.*";
					grammarCheck = "+functionWord";
				}

				if (analysis.contains(grammarCheck)) {
					String lemma = analysis.replaceAll(grammar, "");
					if ((lemma.contains("+") && !lemma.contains("-")) && (aWord.contains("-") && !aWord.contains("+"))) lemma = lemma.replaceAll("\\+", "-");
					if (lemma.contains("+") && !aWord.contains("+")) lemma = lemma.replaceAll("\\+", "");
					return lemma.toLowerCase();
				}
			}
		}

		return null;
	}
}
