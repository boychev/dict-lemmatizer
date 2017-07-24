package com.ontotext.gate.dictlemm.test;

import gate.*;
import gate.creole.ConditionalSerialAnalyserController;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import gate.util.persistence.PersistenceManager;
import org.apache.log4j.Logger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class DictLemmatizerIT {
	private static final Logger LOGGER = Logger.getLogger(DictLemmatizerIT.class);

	private static final String PLUGIN_DIR_PROPERTY_NAME = "pluginDir";
	private static final String PIPELINE_RESOURCE_PATH = "/test-pipeline.gapp";
	private static final String INPUT_CORPUS_RESOURCE_PATH = "/input-documents";
	private static final String OUTPUT_CORPUS_RESOURCE_PATH = "/output-documents";
	private static final int NUM_THREADS = 2;

	private static ConditionalSerialAnalyserController pipeline;
	private static Corpus inputCorpus;
	private static Corpus outputCorpus;

	@BeforeClass
	public static void setup() throws GateException, IOException {
		Gate.runInSandbox(true);
		Gate.init();
		Gate.getCreoleRegister().registerDirectories(new File(System.getProperty(PLUGIN_DIR_PROPERTY_NAME)).toURI().toURL());
		pipeline = (ConditionalSerialAnalyserController) PersistenceManager.loadObjectFromUrl(DictLemmatizerIT.class.getResource(PIPELINE_RESOURCE_PATH));

		FileFilter xmlFileFilter = pathname -> pathname.getName().endsWith(".xml");
		inputCorpus = Factory.newCorpus("input test corpus");
		inputCorpus.populate(DictLemmatizerIT.class.getResource(INPUT_CORPUS_RESOURCE_PATH), xmlFileFilter, StandardCharsets.UTF_8.name(), false);
		outputCorpus = Factory.newCorpus("output test corpus");
		inputCorpus.populate(DictLemmatizerIT.class.getResource(OUTPUT_CORPUS_RESOURCE_PATH), xmlFileFilter, StandardCharsets.UTF_8.name(), false);
	}

	@AfterClass
	public static void cleanup() {
		pipeline.cleanup();
		inputCorpus.cleanup();
		outputCorpus.cleanup();
	}

	@Test
	public void test() throws ResourceInstantiationException {
		LOGGER.info("preparing " + NUM_THREADS + " threads");
		Thread[] threads = new Thread[NUM_THREADS];

		for (int i = 0; i < NUM_THREADS; i++) {
			ConditionalSerialAnalyserController pipeline = (ConditionalSerialAnalyserController) Factory.duplicate(DictLemmatizerIT.pipeline);
			Corpus inputCorpus = (Corpus) Factory.duplicate(DictLemmatizerIT.inputCorpus);

			threads[i] = new Thread() {

				@Override
				public void run() {
					try {
						verifyLemmas(pipeline, inputCorpus, outputCorpus);
					} catch (ExecutionException e) {
						Assert.fail(e.getMessage());
					}
				}
			};
		}

		LOGGER.info("running " + NUM_THREADS + " threads");
		for (Thread thread : threads) thread.start();
	}

	private static void verifyLemmas(ConditionalSerialAnalyserController pipeline, Corpus inputCorpus, Corpus outputCorpus) throws ExecutionException {
		pipeline.setCorpus(inputCorpus);
		pipeline.execute();

		SortedSet<String> actualLemmatizations = lemmatizations(inputCorpus);
		SortedSet<String> xpectedLemmatizations = lemmatizations(outputCorpus);
		Assert.assertEquals("unexpected lemmatizations", xpectedLemmatizations, actualLemmatizations);
	}

	private static SortedSet<String> lemmatizations(Corpus corpus) {
		SortedSet<String> lemmatizations = new TreeSet<>();

		for (Document document : corpus) {
			Set<String> annotationSetNames = document.getAnnotationSetNames();

			for (String annotationSetName : annotationSetNames) {

				for (Annotation annotation : document.getAnnotations(annotationSetName)) {
					FeatureMap features = annotation.getFeatures();

					String string = (String) features.get("string");
					Assert.assertNotNull("missing \"string\" feature from annotation: " + annotation.toString(), string);

					String lemma = (String) features.get("lemma");
					Assert.assertNotNull("missing \"lemma\" feature from annotation: " + annotation.toString(), lemma);

					lemmatizations.add("\"" + string + "\" => \"" + lemma + "\"");
				}
			}
		}

		return lemmatizations;
	}
}
