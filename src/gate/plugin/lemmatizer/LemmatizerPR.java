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


package gate.plugin.lemmatizer;


import gate.*;
import gate.api.AbstractDocumentProcessor;
import gate.creole.ResourceData;
import gate.creole.metadata.*;
import gate.util.GateRuntimeException;
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
@CreoleResource(name = "Lemmatizer",
        helpURL = "https://github.com/GateNLP/gateplugin-Lemmatizer/wiki/Lemmatizer",
        comment = "Find the lemmata of words.")
public class LemmatizerPR  extends AbstractDocumentProcessor {

  private static final long serialVersionUID = 1L;
  
  
  
  protected String inputASName = "";
  @RunTime
  @Optional
  @CreoleParameter(
          comment = "Input annotation set",
          defaultValue = "")
  public void setInputAnnotationSet(String ias) {
    inputASName = ias;
  }

  public String getInputAnnotationSet() {
    return inputASName;
  }
  
  
  protected String inputType = "";
  @RunTime
  @CreoleParameter(
          comment = "The input annotation type",
          defaultValue = "Token")
  public void setInputAnnotationType(String val) {
    this.inputType = val;
  }

  public String getInputAnnotationType() {
    return inputType;
  }

  @RunTime
  @Optional
  @CreoleParameter(
          comment = "The optional containing annotation set type",
          defaultValue = "")
  public void setContainingAnnotationType(String val) {
    this.containingType = val;
  }

  public String getContainingAnnotationType() {
    return containingType;
  }
  protected String containingType = "";

  @RunTime
  @Optional
  @CreoleParameter(
          comment = "The feature from the input annotation to use as word string, if left blank the document text",
          defaultValue = "")
  public void setTextFeature(String val) {
    this.textFeature = val;
  }

  public String getTextFeature() {
    return textFeature;
  }
  protected String textFeature = "";

  @RunTime
  @Optional
  @CreoleParameter(
    comment = "The feature that contains the POS tag",
    defaultValue = "category")
  public void setPosFeature(String val) {
    posFeature = val;
  }
  public String getPosFeature() {
    return posFeature;
  }
  private String posFeature = "category";
  
  private String lemmaFeature = "lemma";
  
  @RunTime
  @CreoleParameter(
    comment = "The name of the feature that should contain the lemma, if any.",
    defaultValue = "lemma"
  )
  public void setLemmaFeature(String val) {
    lemmaFeature = val;
  }
  public String getLemmaFeature() {
    return lemmaFeature;
  }

  
  private String languageCode;
  @RunTime
  @CreoleParameter( 
          comment = "The language code to use, e.g. en, de, fr",
          defaultValue = "en"
  )
  public void setLanguageCode(String val) {
    languageCode = val;
  }
  public String getLanguageCode() { return languageCode; }
  
  
  ////////////////////// FIELDS
  Map<String, String> nounDic;
  Map<String, String> adjDic;
  Map<String, String> advDic;
  Map<String, String> verbDic;
  Map<String, String> detDic;
  Map<String, String> pronDic;
  Map<String, String> adpDic;
  
  String textFeatureToUse = "";
  String posFeatureToUse = "category";
  String lemmaFeatureToUse = "lemma";
  
  String loadedDicts = "";
  String loadedFst = "";
  
  int nrTokens = 0;
  int nrErrors = 0;
  int nrHfst = 0;
  
  HfstLemmatizer hfstLemmatizer = null;  // if null we do not have a FST
  
  // If this is true, the Hfst will always be suppressed.
  // This can only be set (for debugging) by setting to propery 
  // gateplugin-Lemmatizer.noHfst to something other than the string "false"; 
  private boolean noHfst = false;
  
  
  ////////////////////// PROCESSING
  
  @Override
  protected Document process(Document document) {
    
    AnnotationSet inputAS = null;
    if (inputASName == null
            || inputASName.isEmpty()) {
      inputAS = document.getAnnotations();
    } else {
      inputAS = document.getAnnotations(inputASName);
    }

    AnnotationSet inputAnns = null;
    if (inputType == null || inputType.isEmpty()) {
      throw new GateRuntimeException("Input annotation type must not be empty!");
    }
    inputAnns = inputAS.get(inputType);

    AnnotationSet containingAnns = null;
    if (containingType == null || containingType.isEmpty()) {
      // leave the containingAnns null to indicate we do not use containing annotations
    } else {
      containingAnns = inputAS.get(containingType);
      //System.out.println("DEBUG: got containing annots: "+containingAnns.size()+" type is "+containingAnnotationType);
    }

    fireStatusChanged("Lemmatizer: running on " + document.getName() + "...");
    
    if (containingAnns == null) {
      doIt(document,inputAnns);
    } else {
      // do it for each containing annotation
      for (Annotation containingAnn : containingAnns) {
        doIt(document,gate.Utils.getContainedAnnotations(inputAnns,containingAnn));
      }
    }
    
    fireProcessFinished();
    fireStatusChanged("Lemmatizer: processing complete!");
    return document;
  }
  
  private void doIt(Document doc, AnnotationSet anns) {
    for(Annotation token : anns) {
      FeatureMap fm = token.getFeatures();
      String pos = (String)fm.get(posFeatureToUse);
      if(pos == null || pos.trim().isEmpty()) {
        continue;
      } else {
        lemmatize(token,fm,pos);
      }
    }
  }
  
  private void lemmatize(Annotation token, FeatureMap fm, String pos) {
    String lemmatizeStatus = "";   // an indication how we did the lemmatization for this token
    nrTokens += 1;
    String tokenString;
    if (textFeatureToUse == null) {
      tokenString = gate.Utils.cleanStringFor(document, token);
    } else {
      tokenString = (String) fm.get(textFeatureToUse);
    }
    String kind = ((String) fm.get("kind")).toLowerCase();
    String lemma = null;  // as long as the lemma is null we can still try to find one ...
    if (kind.equals("number")) {
      lemma = tokenString;
      lemmatizeStatus = "number";
    } else if (kind.equals("punct")) {
      lemma = tokenString;
      lemmatizeStatus = "punct";
    //} else if (detDic.get(tokenString.toLowerCase()) != null) {
    //  lemma = tokenString;
    //  lemmatizeStatus = "determiner";
    } else {

      // TODO: why is this done????
      //String lemma = null;
      //String posType = posTaggedVersion[s];
      //if ("it".equalsIgnoreCase(language)) {
      //  posType = posType.substring(0, 1);
      //}
      //System.out.println(posType);
      //String generalType = posMap.get(posType.toLowerCase());
      
      // This is based on the Universial POS tags, see http://universaldependencies.org/u/pos/
      if ("ADJ".equalsIgnoreCase(pos)) {
        lemma = adjDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "ADJ";
      } else if ("ADP".equalsIgnoreCase(pos)) {
        lemma = adpDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "ADP";
      } else if ("ADV".equalsIgnoreCase(pos)) {
        lemma = advDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "ADV";
      // MISSING: AUX, CCONJ
      } else if ("DET".equalsIgnoreCase(pos)) {
        lemma = detDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "DET";
      // MISSSING: INTJ
      } else if ("NOUN".equalsIgnoreCase(pos)) {
        lemma = nounDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "NOUN";
      // MISSING: NUM, PART
      } else if ("PRON".equalsIgnoreCase(pos)) {
        lemma = pronDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "PRON";
      // MISSING: PROPN, PUNCT, SCONJ, SYM
      } else if ("VERB".equalsIgnoreCase(pos)) {
        lemma = verbDic.get(tokenString.toLowerCase());
        lemmatizeStatus = "VERB";
      // MISSING: X
      } else {
        lemmatizeStatus = "UNHANDLEDPOS-"+pos;        
      }
      if(lemma == null) {
        lemmatizeStatus += "-NOTFOUND";
      } else {
        lemmatizeStatus += "-FOUND";
      }
      // TODO: replace with indicator of if we have a FST from the init phase
      if (lemma == null && hfstLemmatizer != null && !noHfst) {        
          try {
            nrHfst += 1;
            lemma = hfstLemmatizer.getLemma(tokenString,pos);
            if(lemma != null && !lemma.isEmpty()) {
              lemmatizeStatus += "-HFST_HAVE";
            } else {
              lemma = tokenString;
              lemmatizeStatus += "-HFST_EMPTY";
            }
          } catch (Exception ex) {
            System.err.println("Exception for "+tokenString+": "+ex.getClass()+", "+ex.getMessage());
            ex.printStackTrace(System.err);
            lemma = tokenString;
            lemmatizeStatus += "-HFST_ERROR";
            nrErrors += 1;
          }
      }
      // NOTE: this will only happen if we did not find a lemma in the dictionary and 
      // HFST was not used for some reason
      if(lemma == null) {
        lemma = tokenString;
        lemmatizeStatus += "-NOHFST";
      }
    }
    fm.put(lemmaFeatureToUse, lemma);
    fm.put("lemmatizer.status",lemmatizeStatus);
  }
  

  @Override
  protected void beforeFirstDocument(Controller ctrl) {
    
    nrTokens = 0;
    nrErrors = 0;
    nrHfst = 0;
    
    if(posFeature == null || posFeature.trim().isEmpty()) {
      posFeatureToUse = "category";      
    } else {
      posFeatureToUse = posFeature;
    }
    
    if(textFeature == null || textFeature.trim().isEmpty()) {
      textFeatureToUse = null;
    } else {
      textFeatureToUse = textFeature;
    }
  
    if(lemmaFeature == null || lemmaFeature.trim().isEmpty()) {
      lemmaFeatureToUse = "lemma";
    } else {
      lemmaFeatureToUse = lemmaFeature;
    }
    
    ResourceData myResourceData =
        Gate.getCreoleRegister().get(this.getClass().getName());
    java.net.URL creoleXml = myResourceData.getXmlFileUrl();
    File pluginDir = gate.util.Files.fileFromURL(creoleXml).getParentFile();
    File resourcesDir = new File(pluginDir,"resources");
    File dictDir = new File(new File(resourcesDir,"dictionaries"),languageCode);
    if(!dictDir.exists()) {
      throw new GateRuntimeException("No dictionaries found for language "+languageCode);
    }
    if(!loadedDicts.equals(languageCode)) {
      System.err.println("Lemmatizer: loading dictionaries for "+languageCode);
      adjDic = loadDictionary(new File(dictDir,"adjDic.txt.gz"));
      adpDic = loadDictionary(new File(dictDir, "adpDic.txt.gz"));
      advDic = loadDictionary(new File(dictDir,"advDic.txt.gz"));
      detDic = loadDictionary(new File(dictDir,"detDic.txt.gz"));
      nounDic = loadDictionary(new File(dictDir,"nounDic.txt.gz"));
      pronDic = loadDictionary(new File(dictDir,"pronounDic.txt.gz"));
      verbDic = loadDictionary(new File(dictDir,"verbDic.txt.gz"));
      System.err.println("Lemmatizer: dictionaries loaded");
      loadedDicts = languageCode;
    }
    
    // Load the hfst lemmatizer if it exists for the language, otherwise
    // the hfstLemmatizer variable remains null
    File lemmatizerDir = new File(resourcesDir,"lemmaModels");
    File lemmatizerFile = new File(lemmatizerDir,languageCode+".hfst.ol");
    if(lemmatizerFile.exists()) {
      if(!loadedFst.equals(languageCode)) {
        try {
          String noHfstProp = System.getProperty("gateplugin-Lemmatizer.noHfst");
          if(noHfstProp != null && !noHfstProp.toLowerCase().equals("false")) {
            System.err.println("DEBUG: gateplugin-Lemmatizer.noHfst is set, not using  HFST");
            noHfst = true;
          } else {
            noHfst = false;
            System.err.println("Lemmatizer: loading HFST model for "+languageCode);
            hfstLemmatizer = HfstLemmatizer.load(lemmatizerFile,languageCode);
            System.err.println("Lemmatizer: HFST model loaded");
          }
        } catch (Exception ex) {
          throw new GateRuntimeException("Could not load lemmatization transducer "+lemmatizerFile,ex);
        }
        loadedFst = languageCode;
      }
    } else {
      hfstLemmatizer = null;
    }
    
  }
    

  @Override
  protected void afterLastDocument(Controller ctrl, Throwable t) {
    System.err.println("Tokens processed:                  "+nrTokens);
    System.err.println("Tokens for which HFST was invoked: "+nrHfst);
    System.err.println("Tokens for which HFST had errors:  "+nrErrors);
  }

  @Override
  protected void finishedNoDocument(Controller ctrl, Throwable t) {
  }
  

  
  public static Map<String, String> loadDictionary(File dictFile) {
    if(!dictFile.exists()) {
      System.err.println("LemmatizerPR: WARNING - dictionary file does not exist:  "+dictFile.getAbsolutePath());
    }
    BufferedReader in = null;
    try {
      Map<String, String> map = new HashMap<String, String>();
      in = new BufferedReader(
              new InputStreamReader(
                      new GZIPInputStream(
                              new FileInputStream(dictFile)), "UTF-8"));
      String str;
      while ((str = in.readLine()) != null) {
        if (!"".equals(str.trim())) {
          String values[] = str.split("===");
          if (values.length == 2) {
            String vals[] = values[1].split(";");
            for (int i = 0; i < vals.length; i++) {
              String val = vals[i];
              map.put(val.toLowerCase(), values[0].trim());

            }
          }
        }
      }
      in.close();
      return map;
    } catch (Exception ex) {
      throw new GateRuntimeException("Could not read dictionary " + dictFile.getAbsolutePath(), ex);
    }
  }
  
  
  
  
  
} // class Lemmatizer
