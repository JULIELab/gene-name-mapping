package de.julielab.jules.ae.genemapping;

import de.julielab.jcore.types.EntityMention;
import de.julielab.jcore.types.GeneResourceEntry;
import de.julielab.jcore.utility.JCoReTools;
import de.julielab.jules.ae.genemapping.genemodel.GeneDocument;
import de.julielab.jules.ae.genemapping.genemodel.GeneDocumentFactory;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.utils.ContextUtils;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import de.julielab.jules.ae.genemapping.utils.GeneMapperRuntimeException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.search.BooleanQuery;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSArray;
import org.apache.uima.resource.ResourceInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GeneMappingAnnotator extends JCasAnnotator_ImplBase {
    public static final String COMPONENT_ID = GeneMapping.class.getCanonicalName();

    public static final String CONTEXT_WINDOW_SIZE = "ContextWindowSize";
    public static final String TOKEN_CONTEXT = "TokenContext";
    public static final String ENTITY_MAPPING_TYPES = "EntityMappingTypes";
    public static final String MAPPER_CONFIG_FILE = "MapperConfigFile";

    private static final Logger log = LoggerFactory.getLogger(GeneMappingAnnotator.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.000");
    @ConfigurationParameter(name = ENTITY_MAPPING_TYPES, description = "A list of fully qualified UIMA entity types and regexp patterns which will be applied to the specificType attribute. Each line in the string array is assumed to have the following format: <class-name-of-entity>=<specType regexes> where the specTypes should be delimited with a '\'. An entity will be mapped as a gene/protein if any of the given regular expressions match its specificType feature value.")
    private String[] entityTypeMappings;
    @ConfigurationParameter(name = MAPPER_CONFIG_FILE, description = "A properties file containing configuration settings for the mapping.")
    private String mapperConfigFile;
    @ConfigurationParameter(name = TOKEN_CONTEXT)
    private Boolean useTokenContext;
    @ConfigurationParameter(name = CONTEXT_WINDOW_SIZE, description = "The size - in the number of tokens - to be used for the window around an entity mention to be mapped.", mandatory = false)
    private Integer contextTokenWindowSize;

    private HashMap<String, Matcher> entityMappingTypes = null;
    private GeneMapping mapper = null;

    /**
     * initiaziation of GeneMapper: load the index, get some parameters
     */
    public void initialize(UimaContext aContext) throws ResourceInitializationException {

        // invoke default initialization
        super.initialize(aContext);
        // instantiate mapper (given its properties file from descriptor)
        instantiateMapper(aContext);
        // get entity mapping types (given definition in descriptor)
        getEntityMappingTypes(aContext);
        // check whether abbreviations should be extended (optional parameter)

        // get parameter for token-wise processing
        useTokenContext = (Boolean) aContext.getConfigParameterValue(TOKEN_CONTEXT);


        // set window size
        contextTokenWindowSize = useTokenContext ? Integer.valueOf(Optional.ofNullable((String)aContext.getConfigParameterValue(CONTEXT_WINDOW_SIZE)).orElse("50")) : 0;

        try {
            GeneDocumentFactory.initialize(mapper);
        } catch (GeneMappingException e) {
            log.error("Could not initialize the GeneDocumentFactory", e);
            throw new ResourceInitializationException(e);
        }

        logConfigurationParameters();
    }

    private void logConfigurationParameters() {
        log.info("{}: {}", CONTEXT_WINDOW_SIZE, contextTokenWindowSize);
        log.info("{}: {}", TOKEN_CONTEXT, useTokenContext);
        log.info("{}: {}", CONTEXT_WINDOW_SIZE, contextTokenWindowSize);
        log.info("{}: {}", MAPPER_CONFIG_FILE, mapperConfigFile);
    }

    /**
     * get the types (uima annotatins) to be mapped from the descriptor. Fills a
     * hashmap with entity types and regexp patterns which will be applied to
     * the specificType attribute. each line in the String array entTypes is
     * assumed to have the following format: <class-name-of-entity>= <specTypes>
     * where the specTypes should be delimited with a "|"
     *
     * @param aContext The UIMA context.
     * @throws ResourceInitializationException If there is an initialization issue.
     */
    private void getEntityMappingTypes(UimaContext aContext) throws ResourceInitializationException {
        entityTypeMappings = (String[]) aContext.getConfigParameterValue(ENTITY_MAPPING_TYPES);
        if (entityTypeMappings != null) {
            entityMappingTypes = new HashMap<>();
            for (String entityTypeMapping : entityTypeMappings) {
                String[] entDefinition = entityTypeMapping.split("=");
                if (entDefinition.length != 2) {
                    log.error("EntityMappingTypes in wrong format: {}", entityTypeMapping);
                }
                String entName = entDefinition[0];
                Pattern entSpecificPattern = Pattern.compile(entDefinition[1]);
                entityMappingTypes.put(entName, entSpecificPattern.matcher(""));
            }
            if (log.isInfoEnabled())
            log.info("Entity types to be considered for mapping: {}", entityMappingTypes.keySet().stream().collect(Collectors.toMap(Function.identity(), key -> entityMappingTypes.get(key).pattern())));
        } else {
            String msg = "No entity mapping types defined. Please check the value of the " + ENTITY_MAPPING_TYPES + " parameter.";
            log.error(msg);
            throw new ResourceInitializationException(new IllegalArgumentException(msg));
        }
    }

    /**
     * Gets parameters for mapper and instantiates one. Make an instance of the
     * gene mapper to be used first read mapping configuration from aContext
     * then read type of mapper from aContext
     */
    private void instantiateMapper(UimaContext aContext) throws ResourceInitializationException {
        mapperConfigFile = (String) aContext.getConfigParameterValue(MAPPER_CONFIG_FILE);
        if (mapperConfigFile != null) {
            try {
                mapper = new GeneMapping(new File(mapperConfigFile));
            } catch (CorruptIndexException e) {
                log.error("Error initializing gene mapper: index corrupt.", e);
                throw new ResourceInitializationException(e);
            } catch (Exception e) {
                log.error("Error initializing gene mapper.", e);
                throw new ResourceInitializationException(e);
            }
        } else {
            final String msg = "Error initializing gene mapper: no config file for mapper specified.";
            log.error(msg);
            throw new ResourceInitializationException(new IllegalArgumentException(msg));
        }
    }



    /*
     * functions that do the mapping
     */

    /**
     * The process method. loop over all entity types to be considered and do a
     * mapping for all entities of this type
     */
    public void process(JCas aJCas) throws AnalysisEngineProcessException {


        // get the abstract text and the context query (used later in the map
        // method)
        try {
            Function<EntityMention, Pair<String, BooleanQuery>> contextFun;
            // if abstract text or the complete text is used for mapping
            if (!useTokenContext) {
                BooleanQuery contextQuery = ContextUtils.makeContextQuery(aJCas);
                // The function just returns the whole document query since we do not use a token context window
                contextFun = em -> new ImmutablePair<>(aJCas.getDocumentText(), contextQuery);

            } else {
                // This function retrieves the context around the concrete entity mention as mapping context information.
                contextFun = em -> {
                    try {
                        String entityContext = ContextUtils.makeContext(aJCas,
                                contextTokenWindowSize, em);
                        if (entityContext != null)
                            entityContext = entityContext.trim();
                        BooleanQuery contextQuery = ContextUtils.makeContextQuery(entityContext);
                        return new ImmutablePair<>(entityContext, contextQuery);
                    } catch (IOException e) {
                        throw new GeneMapperRuntimeException(e);
                    }
                };

            }
            // Populate a GeneDocument instance of all the text data (sentences, PoS, Chunks, Abbreviation, ...)
            // except the genes. Those are selected below.
            final GeneDocument geneDocument = GeneDocumentFactory.getInstance().createGeneDocument(aJCas, entityMappingTypes, contextFun);
            doMapping(aJCas, geneDocument);
        } catch (IOException e) {
            String info = "Error generating the boolean context query";
            AnalysisEngineProcessException e1 = new AnalysisEngineProcessException(e);
            log.error(info, e);
            throw e1;
        }
    }

    private void doMapping(JCas aJCas, GeneDocument geneDocument) throws AnalysisEngineProcessException {
        // Now that we have added all GeneMentions to the document, we can do the mapping.
        try {
            mapper.map(geneDocument);
        } catch (GeneMappingException e) {
            final String msg = "Document with ID " + geneDocument.getId() + " could not be gene/protein ID-mapped.";
            log.error(msg, e);
            throw new AnalysisEngineProcessException(new IllegalStateException(msg));
        }
        writeMappingsToCAS(aJCas, geneDocument);
    }


    /**
     * write the found hits as DB entries to the CAS. If we are doing an entity
     * mention mapping, tie these DB entries to their entity annotation.
     *
     * @param aJCas    where to write it
     * @param document The document with the mapped genes.
     */
    private void writeMappingsToCAS(JCas aJCas, GeneDocument document) {
        if (document.getGenes().map(GeneMention::getMentionMappingResult).noneMatch(r -> r.resultEntries != MentionMappingResult.REJECTION)) {
            log.debug("No genes in document {} have been accepted or no gene mentions are present.", document.getId());
            return;
        }

        final List<GeneMention> mappedGenes = document.getGenes().filter(gm -> gm.getMentionMappingResult().resultEntries != MentionMappingResult.REJECTION).collect(Collectors.toList());
        for (GeneMention gm : mappedGenes) {
            final List<SynHit> resultEntries = gm.getMentionMappingResult().resultEntries;
            List<GeneResourceEntry> newResourceEntries = new ArrayList<>(resultEntries.size());
            for (SynHit hit : resultEntries) {
                // organism dependent hits
                GeneResourceEntry resourceEntry = new GeneResourceEntry(aJCas);
                resourceEntry.setSource("WRITE SOURCE INTO INDEX");
                String geneId = hit.getId();

                resourceEntry.setEntryId(geneId);
                resourceEntry.setTaxonomyId(hit.getTaxId());
                resourceEntry.setBegin(gm.getBegin());
                resourceEntry.setEnd(gm.getEnd());
                resourceEntry.setComponentId(COMPONENT_ID);
                String confidence = DECIMAL_FORMAT.format(hit.getMentionScore()) + " / "
                        + DECIMAL_FORMAT.format(hit.getSemanticScore());
                resourceEntry.setConfidence(confidence);
                resourceEntry.setId(gm.getNormalizedText());
                resourceEntry.setSynonym(hit.getSynonym());

                newResourceEntries.add(resourceEntry);
            }

            EntityMention entity = (EntityMention) gm.getOriginalMappedObject();
            FSArray resourceEntryList = entity.getResourceEntryList();
            if (null == resourceEntryList && newResourceEntries.size() > 0)
                resourceEntryList = new FSArray(aJCas, newResourceEntries.size());
            FSArray newEntryList = JCoReTools.addToFSArray(resourceEntryList, newResourceEntries);
            entity.setResourceEntryList(newEntryList);
        }

    }


}
