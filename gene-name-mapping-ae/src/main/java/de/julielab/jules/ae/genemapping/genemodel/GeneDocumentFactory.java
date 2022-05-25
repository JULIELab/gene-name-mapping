package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.java.utilities.spanutils.OffsetMap;
import de.julielab.java.utilities.spanutils.OffsetSet;
import de.julielab.jcore.types.*;
import de.julielab.jcore.utility.JCoReTools;
import de.julielab.jules.ae.genemapping.GeneMapping;
import de.julielab.jules.ae.genemapping.GeneMappingAnnotator;
import de.julielab.jules.ae.genemapping.GeneMappingConfiguration;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.search.BooleanQuery;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.Type;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;

import static java.util.stream.Collectors.toSet;

public class GeneDocumentFactory {
    private final static Logger log = LoggerFactory.getLogger(GeneDocumentFactory.class);
    private static GeneDocumentFactory instance;
    private final GeneMapping geneMapping;

    private GeneDocumentFactory(GeneMapping mapper) {
        GeneMappingConfiguration configuration = mapper.getConfiguration();
        geneMapping = mapper;
    }

    public static GeneDocumentFactory getInstance() {
        if (instance == null)
            throw new IllegalStateException("The initialize(GeneMapper) method must be called before the factory can be used.");
        return instance;
    }

    public static void initialize(GeneMapping mapper) throws GeneMappingException {
        instance = new GeneDocumentFactory(mapper);
    }

    /**
     * <p>Creates a document by setting all required information from the <tt>jCas</tt>.</p>
     * <p>Items added are:
     * <ul>
     * <li>Title text</li>
     * <li>Text body</li>
     * <li>Sentences</li>
     * <li>PoS tags</li>
     * <li>Chunks</li>
     * <li>Acronyms</li>
     * <li>MeSH Headings (from which species headings will be recognized)</li>
     * <li>Species text mentions</li>
     * <li>The genes, according to the <tt>entityMappingTypes</tt>. This is a mapping from qualified UIMA types to matchers that need to match the EntityMentions' <tt>specificType</tt> feature in order for the <tt>EntityMention</tt> to be used as a gene mention.</li>
     * </ul>
     * </p>
     *
     * @param jCas               The JCas to create the <tt>GeneDocument</tt> form.
     * @param entityMappingTypes This is a mapping from qualified UIMA types to matchers that need to match the EntityMentions' <tt>specificType</tt> feature in order for the <tt>EntityMention</tt> to be used as a gene mention.
     * @param contextFun         A function that delivers the context information for each gene mention.
     */
    public GeneDocument createGeneDocument(JCas jCas, Map<String, Matcher> entityMappingTypes, Function<EntityMention, Pair<String, BooleanQuery>> contextFun) throws AnalysisEngineProcessException {
        final GeneDocument doc = new GeneDocument();
        final String docId = JCoReTools.getDocId(jCas);
        doc.setId(docId);

        // Set the title
        Title documentTitle = null;
        final Collection<Title> titles = JCasUtil.select(jCas, Title.class);
        if (titles != null) {
            final Optional<Title> titleOpt = titles.stream().filter(t -> t.getTitleType() != null).filter(t -> t.getTitleType().equals("document")).findAny();
            if (titleOpt.isPresent()) {
                documentTitle = titleOpt.get();
                doc.setDocumentTitle(documentTitle.getCoveredText());
            }
        }

        // Set the document text, except of the title
        StringBuilder textBody = new StringBuilder(jCas.getDocumentText());
        if (doc.getDocumentTitle() != null)
            textBody.delete(0, doc.getDocumentTitle().length());
        doc.setDocumentText(textBody.toString());

        // Set sentences
        OffsetSet sentences = new OffsetSet();
        for (Sentence sentence : jCas.<Sentence>getAnnotationIndex(Sentence.type))
            sentences.add(Range.between(sentence.getBegin(), sentence.getEnd()));
        doc.setSentences(sentences);

        // Set PoS tags
        List<PosTag> tags = new ArrayList<>();
        for (Token token : jCas.<Token>getAnnotationIndex(Token.type)) {
            if (token.getPosTag() != null && token.getPosTag().size() > 0) {
                final POSTag uimaPosTag = token.getPosTag(0);
                final PosTag posTag = new PosTag(uimaPosTag.getValue(), Range.between(uimaPosTag.getBegin(), uimaPosTag.getEnd()));
                tags.add(posTag);
            }
        }
        doc.setPosTags(tags);

        // Set chunks / phrases
        final OffsetMap<String> chunks = new OffsetMap<>();
        for (Chunk chunk : jCas.<Chunk>getAnnotationIndex(Chunk.type)) {
            chunks.put(Range.between(chunk.getBegin(), chunk.getEnd()), chunk.getClass().getSimpleName().replace("Chunk", ""));
        }
        doc.setChunks(chunks);

        // Set acronyms and their long forms
        OffsetMap<Acronym> acronyms = new OffsetMap<>();
        for (Abbreviation abb : jCas.<Abbreviation>getAnnotationIndex(Abbreviation.type)) {
            final AbbreviationLongform longform = abb.getTextReference();
            final Acronym acronym = new Acronym(abb.getCoveredText(), abb.getBegin(), abb.getEnd(), new AcronymLongform(longform.getCoveredText(), longform.getBegin(), longform.getEnd()));
            acronyms.put(acronym);
        }
        doc.setAcronyms(acronyms);

        // Set MeSH headings (also important for species)
        List<MeshHeading> mesh = new ArrayList<>();
        try {
            final ManualDescriptor md = JCasUtil.selectSingle(jCas, ManualDescriptor.class);
            if (md instanceof de.julielab.jcore.types.pubmed.ManualDescriptor) {
                de.julielab.jcore.types.pubmed.ManualDescriptor pmMd = (de.julielab.jcore.types.pubmed.ManualDescriptor) md;
                if (pmMd.getMeSHList() != null) {
                    for (int i = 0; i < pmMd.getMeSHList().size(); i++) {
                        final MeshHeading mh = new MeshHeading(pmMd.getMeSHList(i).getDescriptorName());
                        mesh.add(mh);
                    }
                }
                // This does also set the species mesh headings to the document
                doc.setMeshHeadings(mesh);
            }
        } catch (IllegalArgumentException e) {
            // nothing, there just is no manual descriptor
        }

        // Set species / organisms
        OffsetMap<SpeciesMention> speciesMentions = new OffsetMap<>();
        for (Organism organism : jCas.<Organism>getAnnotationIndex(Organism.type)) {
            if (organism.getResourceEntryList() != null && organism.getResourceEntryList().size() > 0) {
                final SpeciesMention speciesMention = new SpeciesMention(organism.getResourceEntryList(0).getEntryId(), organism.getCoveredText());
                speciesMentions.put(Range.between(organism.getBegin(), organism.getEnd()), speciesMention);
            }
        }
        int titleBegin = documentTitle != null ? documentTitle.getBegin() : 0;
        int titleEnd = documentTitle != null ? documentTitle.getEnd() : 0;
        final SpeciesCandidates speciesCandidates = new SpeciesCandidates(titleBegin, titleEnd, doc.getMeshHeadings().stream().map(MeshHeading::getTaxonomyIds).flatMap(Collection::stream).collect(toSet()), speciesMentions);
        doc.setSpecies(speciesCandidates);

        // Finally, set the genes
        setGenesFromJCas(jCas, doc, entityMappingTypes, contextFun);

        return doc;
    }

    private void setGenesFromJCas(JCas aJCas, GeneDocument geneDocument, Map<String, Matcher> entityMappingTypes, Function<EntityMention, Pair<String, BooleanQuery>> contextFun) throws AnalysisEngineProcessException {
        for (String uimaEntityTypeName : entityMappingTypes.keySet()) {
            final Type uimaEntityType = aJCas.getTypeSystem().getType(uimaEntityTypeName);
            if (uimaEntityType == null) {
                log.error("The entity mapping type {} is not contained in the current type system.", uimaEntityTypeName);
                throw new AnalysisEngineProcessException(CASException.JCAS_TYPENOTFOUND_ERROR, new Object[]{uimaEntityTypeName});
            }
            Matcher specTypeMatcher = entityMappingTypes.get(uimaEntityTypeName);
            for (Annotation a : aJCas.getAnnotationIndex(uimaEntityType)) {
                EntityMention em;
                try {
                    em = (EntityMention) a;
                } catch (ClassCastException e) {
                    String msg = "The passed entity type " + uimaEntityType + " is not a subclass of EntityMention. Only subclasses of EntityMention can take part in the ID mapping of this component.";
                    log.error(msg);
                    throw new AnalysisEngineProcessException(new IllegalArgumentException(msg));
                }
                if (em.getSpecificType() != null && specTypeMatcher.reset(em.getSpecificType()).matches()) {
                    // Create a GeneMention object and select it for mapping in the GeneDocument.
                    final Pair<String, BooleanQuery> contextAndContextQuery = contextFun.apply(em);
                    if (contextAndContextQuery == null || contextAndContextQuery.getLeft() == null || contextAndContextQuery.getRight() == null)
                        throw new AnalysisEngineProcessException(new GeneMappingException("The context query for the entity " + em + " of document " + geneDocument.getId() + " could not be created."));
                    final GeneMention gm = createGeneMentionFromUimaAnnotation(em, contextAndContextQuery);
                    geneDocument.addGene(gm);
                } else if (em.getSpecificType() == null) {
                    log.debug("Encountered an entity mention that has no specificType set. Such entities won't be mapped because they don't match any specificType regular expression (see annotator parameter "+ GeneMappingAnnotator.ENTITY_MAPPING_TYPES +").");
                }
            }
        }
        geneDocument.selectAllGenes();
    }

    private GeneMention createGeneMentionFromUimaAnnotation(EntityMention em, Pair<String, BooleanQuery> contextAndContextQuery) {
        final GeneMention gm = new GeneMention(em.getCoveredText(), em.getBegin(), em.getEnd());
        gm.setNormalizer(geneMapping.getMappingCore().getTermNormalizer());
        gm.setDocumentContext(contextAndContextQuery.getLeft());
        gm.setContextQuery(contextAndContextQuery.getRight());
        gm.setOriginalMappedObject(em);
        return gm;
    }
}
