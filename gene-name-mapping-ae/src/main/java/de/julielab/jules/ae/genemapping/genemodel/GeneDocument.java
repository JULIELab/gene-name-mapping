package de.julielab.jules.ae.genemapping.genemodel;

import com.fulmicoton.multiregexp.MultiPatternSearcher;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.lahodiuk.ahocorasick.AhoCorasickOptimized;
import de.julielab.java.utilities.FileUtilities;
import de.julielab.java.utilities.IOStreamUtilities;
import de.julielab.java.utilities.spanutils.OffsetMap;
import de.julielab.java.utilities.spanutils.OffsetSet;
import de.julielab.java.utilities.spanutils.OffsetSpanComparator;
import de.julielab.java.utilities.spanutils.Span;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention.GeneTagger;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class GeneDocument {

    public static final Pattern ecNumberRegExp = Pattern.compile("EC\\s*([0-9]*\\.*)+");
    public static final Pattern lociRegExp = Pattern.compile("[0-9]+[Xqp][0-9.-]+");
    private static final Logger log = LoggerFactory.getLogger(GeneDocument.class);
    /**
     * Sometimes an obvious plural tag is missed, e.g. for words like "LERKs". This
     * matches this exact pattern: Upper case characters followed by a lower case
     * 's'.
     */
    private static final Matcher pluralMatcher = Pattern.compile("[A-Z]+s").matcher("");
    private OffsetMap<Acronym> acronyms;
    private OffsetMap<AcronymLongform> acronymLongforms;
    private OffsetMap<String> chunks;
    private OffsetMap<PosTag> posTags;
    private String documentText;
    private String documentTitle;
    /**
     * This is the original set of genes that has been set via
     * {@link #setGenes(Stream)}. From this set, a subset is selected and stored in
     * {@link #genes} which is then the set of genes used for all processing and
     * mapping. Non-selected genes are non-existent to processing algorithms, except
     * they explicitly work on the allGenes set.
     */
    private List<GeneMention> allGenes;
    private OffsetMap<List<GeneMention>> genes;
    /**
     * Used for evaluation and tagger training purposes.
     */
    private OffsetMap<List<GeneMention>> goldGenes;
    private GeneSets geneSets;
    private String id;
    private OffsetSet sentences;
    private SpeciesCandidates species;
    private AhoCorasickOptimized geneNameDictionary;
    private TermNormalizer termNormalizer;
    private Map<String, String> taxId2Prefix;
    private Map<String, String> prefix2TaxId;
    private Map<String, List<String>> meshHeadings2TaxId;

    private Collection<MeshHeading> meshHeadings;
    private String defaultSpecies;


    public GeneDocument() {
        readSpeciesPrefixes();
        readMeshHeadings2TaxIdMap();
    }

    public GeneDocument(String id) {
        this();
        this.id = id;
    }

    /**
     * Copies the template document. This is mostly a shallow copy, except the
     * genes. Those are deeply copied and put into the respective structures (the
     * "genes" and "geneSets" fields).
     *
     * @param template The document to copy.
     */
    public GeneDocument(GeneDocument template) {
        acronyms = template.acronyms;
        acronymLongforms = template.acronymLongforms;
        chunks = template.chunks;
        posTags = template.posTags;
        documentText = template.documentText;
        documentTitle = template.documentTitle;
        // Copy the genes by their Java system ID
        TreeMap<GeneMention, GeneMention> orgToNew = new TreeMap<>(
                (g1, g2) -> Integer.compare(System.identityHashCode(g1), System.identityHashCode(g2)));
        template.allGenes.forEach(g -> orgToNew.put(g, new GeneMention(g)));
        allGenes = template.allGenes.stream().map(GeneMention::new).collect(Collectors.toList());
        genes = new OffsetMap<>();
        for (Entry<Range<Integer>, List<GeneMention>> original : template.genes.entrySet())
            genes.put(original.getKey(), original.getValue().stream().map(orgToNew::get).collect(Collectors.toList()));
        goldGenes = template.goldGenes;
        geneSets = new GeneSets();
        for (GeneSet gs : template.geneSets) {
            GeneSet newSet = new GeneSet();
            newSet.setFeatureVector(gs.getFeatureVector());
            newSet.setInstance(gs.getInstance());
            newSet.setSetId(gs.getSetId());
            newSet.setSpecificType(gs.getSpecificType());
            gs.forEach(g -> newSet.add(orgToNew.get(g)));
            geneSets.add(newSet);
        }
        id = template.id;
        sentences = template.sentences;
        species = template.species;
        geneNameDictionary = template.geneNameDictionary;
        termNormalizer = template.termNormalizer;
    }


    private void readMeshHeadings2TaxIdMap() {
        try {
            InputStream descriptorMapping = FileUtilities.findResource("/desc2tax.gz");
            if (descriptorMapping == null)
                descriptorMapping = FileUtilities.findResource("/desc2tax");
            meshHeadings2TaxId = IOStreamUtilities.getLinesFromInputStream(descriptorMapping).stream().map(line -> line.split("\t")).collect(Collectors.groupingBy(split -> split[0], Collectors.mapping(split -> split[1], Collectors.toList())));
        } catch (IOException e) {
            log.warn("Could not read the mapping from descriptor names to taxonomy IDs at the classpath resource /desc2tax.gz or /desc2tax. Taxonomy ID recognition quality will be decreased.");
        }
    }

    private void readSpeciesPrefixes() {
        try {
            taxId2Prefix = IOStreamUtilities.getLinesFromInputStream(getClass().getResourceAsStream("/speciesprefixes.map")).stream().map(line -> line.split("\t")).collect(Collectors.toMap(split -> split[1], split -> split[0]));
            prefix2TaxId = IOStreamUtilities.getLinesFromInputStream(getClass().getResourceAsStream("/speciesprefixes.map")).stream().map(line -> line.split("\t")).collect(Collectors.toMap(split -> split[0], split -> split[1]));
            log.debug("Loaded species prefix map: {}", taxId2Prefix);
        } catch (IOException e) {
            log.warn("Could not read the species prefixes map which helps with species disambiguation. Species recognition performance will be somewhat lower. This is not a critical error, execution can continue.", e);
        }
    }

    public AcronymLongform getAcronymLongformAndOffsets(Acronym acronym) {
        AcronymLongform longform = acronym.getLongform();
        if (null == longform.getText()) {
            Range<Integer> range = longform.getOffsets();
            longform.setText(this.getDocumentText().substring(range.getMinimum(), range.getMaximum()));
        }
        return longform;
    }

    public OffsetMap<Acronym> getAcronyms() {
        return acronyms;
    }

    public void setAcronyms(OffsetMap<Acronym> acronyms) {
        this.acronyms = acronyms;
    }

    public void setAcronyms(Acronym... acronyms) {
        setAcronyms(Stream.of(acronyms));
    }

    public void setAcronyms(Collection<Acronym> acronyms) {
        setAcronyms(acronyms.stream());
    }

    public void setAcronyms(Stream<Acronym> acronyms) {
        this.acronyms = new OffsetMap<>();
        this.acronymLongforms = new OffsetMap<>();
        acronyms.forEach(a -> {
            this.acronyms.put(a.getOffsets(), a);
            this.acronymLongforms.put(a.getLongform().getOffsets(), a.getLongform());
        });
    }

    public OffsetMap<AcronymLongform> getAcronymLongforms() {
        return acronymLongforms;
    }

    public OffsetMap<String> getChunks() {
        return chunks;
    }

    public void setChunks(OffsetMap<String> chunks) {
        this.chunks = chunks;
    }

    public String getDocumentText() {
        return documentText;
    }

    public void setDocumentText(String documentText) {
        this.documentText = documentText;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public void setDocumentTitle(String documentTitle) {
        this.documentTitle = documentTitle;
    }

    public OffsetMap<List<GeneMention>> getGeneMap() {
        if (genes == null)
            throw new IllegalStateException(
                    "The internal genes map has to be built first by calling an appropriate method after setting the original set of genes.");
        return genes;
    }

    public Stream<GeneMention> getGeneMentionsAtOffsets(final Range<Integer> offsets) {
        return getGenes().filter(g -> g.getOffsets().isOverlappedBy(offsets));
    }

    /**
     * Returns those genes that have been selected from the original set of all
     * genes. Thus, before this method works, a selection method has to be called
     * first.
     *
     * @return The currently selected genes.
     * @see #selectGeneMentionsByTagger(GeneTagger...)
     * @see #unifyGeneMentionsAtEqualOffsets(GeneTagger...)
     */
    public Stream<GeneMention> getGenes() {
        if (genes == null)
            throw new IllegalStateException(
                    "The internal genes map has to be built first by calling an appropriate method after setting the original set of genes.");
        return genes.values().stream().flatMap(Collection::stream);
    }

    public void setGenes(GeneMention... genes) {
        this.allGenes = new ArrayList<>(genes.length);
        setGenes(Stream.of(genes));
    }

    public void setGenes(Stream<GeneMention> genes) {
        if (this.allGenes != null)
            this.allGenes.clear();
        else
            this.allGenes = new ArrayList<>();
        genes.forEach(this.allGenes::add);
        this.allGenes.forEach(g -> g.setGeneDocument(this));
        this.allGenes.forEach(g -> g.setNormalizer(termNormalizer));
        this.allGenes.forEach(g -> {
            if (g.getTagger() == GeneTagger.GOLD)
                putGoldGene(g);
        });
    }

    public void addGene(GeneMention gene) {
        if (this.allGenes == null)
            this.allGenes = new ArrayList<>();
        allGenes.add(gene);
        gene.setGeneDocument(this);
        gene.setNormalizer(termNormalizer);
        if (gene.getTagger() == GeneTagger.GOLD)
            putGene(gene);
    }

    public void setGenes(Collection<GeneMention> genes) {
        if (this.allGenes == null)
            this.allGenes = new ArrayList<>(genes.size());
        setGenes(genes.stream());
    }

    public Iterable<GeneMention> getGenesIterable() {
        return () -> getGenes().iterator();
    }

    public Iterator<GeneMention> getGenesIterator() {
        return getGenes().iterator();
    }

    /**
     * On first call, creates a trivial GeneSets object where each gene is in its
     * own set. From here, one can begin to agglomerate sets e.g. due to the same
     * name, an acronym connection or other measures. Subsequent calls will return
     * the same set instance.
     *
     * @return A GeneSets object where each gene has its own set.
     */
    public GeneSets getGeneSets() {
        if (this.geneSets != null) {
            return this.geneSets;
        }
        GeneSets geneSets = new GeneSets();
        getGenes().forEach(gm -> {
            GeneSet geneSet = new GeneSet();
            geneSet.add(gm);
            getLastPosTag(gm.getOffsets(), Collections.emptySet())
                    .ifPresent(tag -> geneSet.setPlural(tag.getTag().equals("NNS")));
            geneSets.add(geneSet);
        });
        this.geneSets = geneSets;
        return geneSets;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns acronyms (not full forms!) overlapping with the given range.
     *
     * @param range An offset range.
     * @return Acronyms overlapping the given range.
     */
    public Collection<Acronym> getOverlappingAcronyms(Range<Integer> range) {
        return acronyms.getOverlapping(range).values();
    }

    public Collection<AcronymLongform> getOverlappingAcronymLongforms(Range<Integer> range) {
        return acronymLongforms.getOverlapping(range).values();
    }

    public Range<Integer> getovappingSentence(Span span) {
        return sentences.locate(span.getOffsets());
    }

    public Range<Integer> getovappingSentence(Range<Integer> range) {
        return sentences.locate(range);
    }

    /**
     * Returns chunks overlapping with the given range.
     *
     * @param range An offset range.
     * @return Chunks overlapping the given range.
     */
    public Set<Entry<Range<Integer>, String>> getOverlappingChunks(Range<Integer> range) {
        return chunks.getOverlapping(range).entrySet();
    }

    /**
     * Returns chunks of the given type overlapping with the given range.
     *
     * @param range     An offset range.
     * @param chunkType The chunk type - e.g. ChunkNP - to return.
     * @return Chunks with the given type overlapping the given range.
     */
    public Set<Entry<Range<Integer>, String>> getOverlappingChunks(Range<Integer> range, final String chunkType) {
        return getOverlappingChunks(range).stream().filter(e -> e.getValue().equals(chunkType))
                .collect(Collectors.toSet());
    }

    public Collection<PosTag> getOverlappingPosTags(Range<Integer> range) {
        if (posTags == null)
            return Collections.emptyList();
        return posTags.getOverlapping(range).values();
    }

    public Optional<PosTag> getLastPosTag(Range<Integer> range, Set<String> excludedTags) {
        List<PosTag> posList = getOverlappingPosTags(range).stream().collect(toList());
        if (posList.isEmpty())
            return Optional.empty();
        for (int i = posList.size() - 1; i >= 0; i--) {
            PosTag posTag = posList.get(i);
            if (excludedTags == null || excludedTags.isEmpty() || !excludedTags.contains(posTag.getTag()))
                return Optional.of(posTag);
        }
        return Optional.empty();
    }

    public OffsetMap<PosTag> getPosTags() {
        return posTags;
    }

    public void setPosTags(Collection<PosTag> posTags) {
        setPosTags(posTags.stream());
    }

    public void setPosTags(Stream<PosTag> posTags) {
        this.posTags = new OffsetMap<>();
        posTags.map(pos -> {
            if (pos.getTag().equals("NN") && documentText != null && pos.getEnd() < documentText.length()) {
                pluralMatcher.reset(getCoveredText(pos));

                if (pluralMatcher.matches())
                    pos.setTag("NNS");
            }
            return pos;
        }).forEach(this.posTags::put);
    }

    /**
     * Returns genes overlapping with the given range.
     *
     * @param range An offset range.
     * @return Genes overlapping the given range.
     */
    public Stream<GeneMention> getOverlappingGenes(Range<Integer> range) {
        return genes.getOverlapping(range).values().stream().flatMap(list -> list.stream());
    }

    public Stream<GeneMention> getOverlappingGoldGenes(Range<Integer> range) {
        if (goldGenes == null)
            return Stream.empty();
        return goldGenes.getOverlapping(range).values().stream().flatMap(list -> list.stream());
    }

    public NavigableSet<Range<Integer>> getSentences() {
        return sentences;
    }

    public void setSentences(OffsetSet sentences) {
        this.sentences = sentences;
    }

    public SpeciesCandidates getSpecies() {
        return species;
    }

    public void setSpecies(SpeciesCandidates species) {
        filterSpeciesMentions(species.getTitleCandidates().entrySet().iterator());
        filterSpeciesMentions(species.getTextCandidates().entrySet().iterator());
        this.species = species;
    }

    private void filterSpeciesMentions(Iterator<Entry<Range<Integer>, SpeciesMention>> textSpeciesIt) {
        if (chunks == null || chunks.isEmpty())
            log.warn("To filter organism mentions that should be removed for gene species assignments, chunking is required. Thus, the chunks must be set before the species mentions. At this moment, there are no chunks set and species filtering might be ineffective.");
        while (textSpeciesIt.hasNext()) {
            Entry<Range<Integer>, SpeciesMention> e = textSpeciesIt.next();
            final NavigableMap<Range<Integer>, String> overlapping = chunks.getOverlapping(e.getKey());
            StringBuilder sb = new StringBuilder();
            for (Range<Integer> chunk : overlapping.keySet())
                sb.append(getCoveredText(chunk)).append(" ");
            if (sb.toString().toLowerCase().matches(".*(one|two|bi|three|tri)(-|\\s)hybrid.*"))
                textSpeciesIt.remove();
        }
    }

    /**
     * This will try to map genes to species using a multi-stage procedure as
     * detailed in "Inter-species normalization of gene mentions with GNAT" by
     * Hakenberg et al. (2008).
     *
     * @param gm A mention of a gene
     * @return A map of all mentioned species found on the first stage that contains
     * any. In case no species can be inferred, this will be an empty map.
     * @see GeneSpeciesOccurrence
     */
    public Multimap<String, GeneSpeciesOccurrence> setSpeciesHints(GeneMention gm) {
        Range<Integer> geneOffsets = gm.getOffsets();
        Range<Integer> sentence = sentences.locate(geneOffsets);
        NavigableMap<Range<Integer>, String> sentenceChunks = chunks.restrictTo(sentence);
        Multimap<String, GeneSpeciesOccurrence> mentions = TreeMultimap.create();
        OffsetMap<SpeciesMention> candidates = species.getTextCandidates();
        List<String> meshTaxIds = meshHeadings != null ? meshHeadings.stream().map(MeshHeading::getTaxonomyIds).flatMap(Collection::stream).collect(toList()) : Collections.emptyList();

        if (null != candidates) {
            NavigableMap<Range<Integer>, SpeciesMention> sentenceSpecies = candidates.restrictTo(sentence);
            mentions.putAll(speciesInNounPhrase(geneOffsets, sentenceSpecies, sentenceChunks));
            // Search within the sentence before this.
            // Might have already been the first sentence.
            if (null != sentence) {
                final Range<Integer> previousSentence = sentences.lower(sentence);
                if (previousSentence != null) {
                    // this feature helps in some cases and hurts in others, makes a difference of 0.002 f-score
                    //mentions.putAll(speciesInSentence(candidates, previousSentence, GeneSpeciesOccurrence.PREVIOUS_SENTENCE));
                }
            }
        }
        OffsetMap<SpeciesMention> titleCandidates = species.getTitleCandidates();
        if (!titleCandidates.isEmpty()) {
            for (SpeciesMention speciesMention : titleCandidates.values()) {
                mentions.put(speciesMention.getTaxId(), GeneSpeciesOccurrence.TITLE);
            }
        }
        if (null != candidates) {
            // First sentence of abstract
            // Find the first sentence of the abstract
            Range<Integer> firstSentence = sentences.floor(Range.between(documentTitle.length() + 1, documentTitle.length() + 1));
            if (firstSentence != null && firstSentence.equals(sentences.first()))
                firstSentence = sentences.higher(firstSentence);
            if (firstSentence != null) {
                final Multimap<String, GeneSpeciesOccurrence> speciesInFirstSentence = speciesInSentence(candidates, firstSentence, GeneSpeciesOccurrence.FIRST);
                mentions.putAll(speciesInFirstSentence);
            }
            // Anywhere in the abstract
            if (!candidates.isEmpty()) {
                for (SpeciesMention s : candidates.values()) {
                    mentions.put(s.getTaxId(), GeneSpeciesOccurrence.ANYWHERE);
                }
            }
        }

        if (meshHeadings != null) {
            for (String meshTaxId : meshTaxIds)
                mentions.put(meshTaxId, GeneSpeciesOccurrence.MESH);

        } else {
            for (String s : species.getMeshCandidates()) {
                mentions.put(s, GeneSpeciesOccurrence.MESH);
            }
        }

        // Check if the gene starts with the species prefix of some candidates
        if (prefix2TaxId != null) {
            // Does this gene mention have an eligible species specifier at all?
            final String firstChar = String.valueOf(gm.getText().charAt(0));
            boolean hasSpeciesPrefix = prefix2TaxId.containsKey(firstChar) && gm.getText().length() > 2 && Character.isUpperCase(gm.getText().charAt(1));
            if (hasSpeciesPrefix) {
                final String taxId = prefix2TaxId.get(firstChar);
                mentions.put(taxId, GeneSpeciesOccurrence.SPECIES_PREFIX);
//                for (SpeciesMention s : candidates.values()) {
//                    final String prefix = taxId2Prefix.get(s.getTaxId());
//                    // Read: if the gene mention begins with the respective species prefix and the first character after the prefix is upper-case
//                    if (prefix != null && gm.getText().startsWith(prefix) && gm.getText().length() > prefix.length() && Character.isUpperCase(gm.getText().charAt(prefix.length()))) {
//                    }
//                }
            }
        }

        if (mentions.isEmpty()) {
            // It seems there is absolutely no mention of any species in this document
            // We only assign the default species in this case. We don't want ME approaches to "learn" the default
            // species, this is very biased based on the corpus.
            if (!StringUtils.isBlank(defaultSpecies))
                mentions.put(defaultSpecies, GeneSpeciesOccurrence.DEFAULT);
        }
//        if (mentions.values().contains(GeneSpeciesOccurrence.COMPOUND_PRECEED)) {
//            List<String> toRemove = new ArrayList<>();
//            for (String tax : mentions.keySet()) {
//                if (!mentions.get(tax).contains(GeneSpeciesOccurrence.COMPOUND_PRECEED))
//                    toRemove.add(tax);
//            }
//            toRemove.forEach(mentions::removeAll);
//        }
        gm.setTaxonomyCandidates(mentions);
        return mentions;
    }


    public void removeGenesWithoutCandidates() {
        for (Iterator<Entry<Range<Integer>, List<GeneMention>>> it = genes.entrySet().iterator(); it.hasNext(); ) {
            Entry<Range<Integer>, List<GeneMention>> entry = it.next();
            List<GeneMention> gmList = entry.getValue();
            for (Iterator<GeneMention> genesIt = gmList.iterator(); genesIt.hasNext(); ) {
                GeneMention gm = genesIt.next();
                if (gm.getMentionMappingResult().originalCandidates == null
                        || gm.getMentionMappingResult().originalCandidates.isEmpty())
                    genesIt.remove();
            }
            if (gmList.isEmpty())
                it.remove();
        }
    }


    /**
     * Removes all prefixes belonging to a species, e.g. "human FGF-22" will be
     * turned into "FGF-22"
     *
     * @param searcher A MultiPatternSearcher containing a compiled multi-regex of all
     *                 species to be considered.
     */
    public void removeSpeciesMention(MultiPatternSearcher searcher) {
        allGenes.forEach(gm -> {
            String text = gm.getText();
            MultiPatternSearcher.Cursor cursor = searcher.search(text);
            if (cursor.next()) {
                int start = cursor.start();
                if (start == 0) {
                    int end = cursor.end();
                    gm.setText(text.substring(end));
                    Range<Integer> offsets = gm.getOffsets();
                    int newBegin = offsets.getMinimum() + end;
                    gm.setOffsets(Range.between(newBegin, offsets.getMaximum()));
                }
            }
        });
    }

    /**
     * Builds the internal gene offset map with all available genes, overlapping or
     * not. Offset duplicates will be override items that have been in the offset
     * map before their addition.
     */
    public void selectAllGenes() {
        this.genes = new OffsetMap<>();
        if (allGenes != null)
            this.allGenes.forEach(g -> putGene(g));
    }

    /**
     * Builds the internal gene offset map and only keeps gene mentions found by the
     * given taggers.
     *
     * @param tagger The taggers for which gene mentions should be kept.
     */
    public void selectGeneMentionsByTagger(final GeneTagger... tagger) {
        genes = new OffsetMap<>();
        Set<GeneTagger> includedTaggers = new HashSet<>(Arrays.asList(tagger));
        for (Iterator<GeneMention> it = allGenes.iterator(); it.hasNext(); ) {
            GeneMention g = it.next();
            if (g.getTagger() == null) {
                log.error("Gene {} in document {} does not have a tagger set", g.getText(), g.getDocId());
                it.remove();
            } else {
                if (includedTaggers.contains(g.getTagger())) {
                    putGene(g);
                }
            }
        }
    }

    /**
     * Adds gene mentions to the selected set of gene mentions based on a tagger
     * (optional) and regular expressions matched on the mention string.
     *
     * @param tagger  Optional, may be null
     * @param regExes A list of regular expressions. Each gene mention matching one of
     *                the expressions (and, if given, the tagger) will be added to the
     *                selected list of genes.
     */
    public void allowGeneMentionsByRegularExpression(final GeneTagger tagger, final Pattern... regExes) {
        Matcher[] ms = new Matcher[regExes.length];
        for (int i = 0; i < regExes.length; ++i)
            ms[i] = regExes[i].matcher("");
        for (GeneMention gm : allGenes) {
            // check the tagger
            if (tagger != null && gm.getTagger() != tagger)
                continue;
            // if the tagger was correct (or not given), check all regular
            // expressions for this mention
            boolean allowed = false;
            for (int i = 0; i < regExes.length && !allowed; ++i) {
                ms[i].reset(gm.getText());
                if (ms[i].matches())
                    allowed = true;
            }
            // if at least one mention matched a regular expression, add it to
            // the set of selected genes
            if (allowed)
                putGene(gm);
        }
    }

    /**
     * Searches for species mentions in compound nouns, enclosing phrases and the
     * whole sentence, in this order.
     *
     * @param geneOffsets
     * @param sentenceSpecies
     * @param sentenceChunks
     * @return A map of the taxonomy IDs of all mentioned species and their
     * corresponding reliability.
     */
    private Multimap<String, GeneSpeciesOccurrence> speciesInNounPhrase(Range<Integer> geneOffsets,
                                                                        NavigableMap<Range<Integer>, SpeciesMention> sentenceSpecies,
                                                                        NavigableMap<Range<Integer>, String> sentenceChunks) {
        Multimap<String, GeneSpeciesOccurrence> mentionMap = TreeMultimap.create();

        // No use in trying any further
        if (sentenceSpecies.isEmpty()) {
            return mentionMap;
        }

        final SortedMap<Range<Integer>, SpeciesMention> speciesInMention = sentenceSpecies.subMap(Range.between(geneOffsets.getMinimum(), geneOffsets.getMinimum()), Range.between(geneOffsets.getMaximum(), geneOffsets.getMaximum()));
        for (SpeciesMention speciesMention : speciesInMention.values())
            mentionMap.put(speciesMention.getTaxId(), GeneSpeciesOccurrence.COMPOUND_PRECEED);

        Range<Integer> enclosingChunk = sentenceChunks.floorKey(geneOffsets);
        if (enclosingChunk != null && enclosingChunk.isOverlappedBy(geneOffsets)) {

            // Mention precdedes the gene in the compound
            NavigableMap<Range<Integer>, SpeciesMention> mentions = sentenceSpecies.subMap(Range.between(enclosingChunk.getMinimum(), enclosingChunk.getMinimum()), true,
                    Range.between(geneOffsets.getMaximum(), geneOffsets.getMaximum()), true);
            // Mention is somewhere in this (compound) noun
            if (!mentions.isEmpty()) {
                for (SpeciesMention s : mentions.values()) {
                    mentionMap.put(s.getTaxId(), GeneSpeciesOccurrence.COMPOUND_PRECEED);
                }
            }

            // Mention succeeds the gene in the compound
            mentions = sentenceSpecies.subMap(Range.between(geneOffsets.getMinimum(), geneOffsets.getMinimum()), true,
                    Range.between(enclosingChunk.getMaximum(), enclosingChunk.getMaximum()), true);
            // Mention is somewhere in this (compound) noun
            if (!mentions.isEmpty()) {
                for (SpeciesMention s : mentions.values()) {
                    mentionMap.put(s.getTaxId(), GeneSpeciesOccurrence.COMPOUND_SUCCEED);
                }
            }
        }

        Range<Integer> candidate = sentenceSpecies.floorKey(geneOffsets);
        if (null == candidate) {
            // This sentence, but not in front of gene mention
            for (SpeciesMention s : sentenceSpecies.values()) {
                // If we have already an entry for this tax ID, then it already appeared in a chunk
                if (!mentionMap.containsKey(s.getTaxId()))
                    mentionMap.put(s.getTaxId(), GeneSpeciesOccurrence.SENTENCE);
            }
        } else {
            Entry<Range<Integer>, String> chunk = sentenceChunks.floorEntry(geneOffsets);
            if (null == chunk) {
                chunk = sentenceChunks.firstEntry();
            }

            int start = -1;
            while (chunk.getValue().equals("ChunkNP")) {
                start = chunk.getKey().getMinimum();
                chunk = sentenceChunks.lowerEntry(chunk.getKey());
                if (null == chunk) {
                    break;
                }
            }

            if (start != -1) {
                NavigableMap<Range<Integer>, SpeciesMention> mentions = sentenceSpecies.subMap(Range.between(start, start), true,
                        Range.between(geneOffsets.getMaximum(), geneOffsets.getMaximum()), true);
                for (SpeciesMention s : mentions.values()) {
                    // If we have already an entry for this tax ID, then it already appeared in a chunk
                    if (!mentionMap.containsKey(s.getTaxId()))
                        mentionMap.put(s.getTaxId(), GeneSpeciesOccurrence.PHRASE);
                }
            }

            NavigableMap<Range<Integer>, SpeciesMention> mentions = sentenceSpecies.headMap(Range.between(geneOffsets.getMinimum(), geneOffsets.getMinimum()), true);
            for (SpeciesMention s : mentions.values()) {
                // If we already have an entry for this tax ID, it already appeared in a smaller scope
                if (!mentionMap.containsKey(s.getTaxId()))
                    mentionMap.put(s.getTaxId(), GeneSpeciesOccurrence.SENTENCE);
            }
        }
        return mentionMap;
    }

    private Multimap<String, GeneSpeciesOccurrence> speciesInSentence(
            OffsetMap<SpeciesMention> speciesCandidates, Range<Integer> sentence,
            GeneSpeciesOccurrence order) {
        Multimap<String, GeneSpeciesOccurrence> mentionMap = TreeMultimap.create();
        // No use in trying any further
        if (speciesCandidates.isEmpty()) {
            return mentionMap;
        } else {
            final NavigableMap<Range<Integer>, SpeciesMention> candidatesInSentence = speciesCandidates.restrictTo(sentence);
            for (SpeciesMention s : candidatesInSentence.values()) {
                mentionMap.put(s.getTaxId(), order);
            }
            return mentionMap;
        }
    }

    /**
     * Creates the internal gene map without allowing exact duplicate ranges where
     * begin and end are equal but still allows overlapping.
     *
     * @param taggerPriorities The order in which should be decided which gene mention to keep at
     *                         a given position with multiple candidates at the exact same
     *                         location. A lower position means higher priority. Non-mentioned
     *                         taggers have minimum priority, e.g. are most easily discarded.
     */
    public void unifyGeneMentionsAtEqualOffsets(final GeneTagger... taggerPriorities) {
        genes = new OffsetMap<>();
        Map<GeneTagger, Integer> priorities = new HashMap<>();
        IntStream.range(0, taggerPriorities.length).forEach(i -> priorities.put(taggerPriorities[i], i));
        for (GeneMention gm : allGenes) {
            List<GeneMention> genesAtOffset = genes.get(gm.getOffsets());
            if (genesAtOffset == null) {
                putGene(gm);
            } else {
                for (GeneMention gmInMap : genesAtOffset) {
                    int priorityInMap = priorities.getOrDefault(gmInMap.getTagger(), Integer.MAX_VALUE);
                    int gmPriority = priorities.getOrDefault(gm.getTagger(), Integer.MAX_VALUE);
                    if (gmPriority > priorityInMap)
                        replaceGene(gmInMap, gm);
                }
            }
        }
    }

    public void unifyAcronymsLongerFirst() {
        TreeSet<Span> unifiedSet = unifySpanLongerFirst(acronyms.values());
        acronyms = new OffsetMap<>();
        unifiedSet.forEach(g -> acronyms.put(g.getOffsets(), (Acronym) g));
    }

    /**
     * Unifies all genes with the longer-span-first strategy.
     */
    public void unifyAllGenesLongerFirst() {
        TreeSet<Span> unifiedSet = unifySpanLongerFirst(allGenes);
        genes = new OffsetMap<>();
        unifiedSet.forEach(g -> putGene((GeneMention) g));
    }

    public void unifyAllGenesLongerFirst(GeneTagger... taggers) {
        selectGeneMentionsByTagger(taggers);
        TreeSet<Span> unifiedSet = unifySpanLongerFirst(
                genes.values().stream().flatMap(list -> list.stream()).collect(Collectors.toList()));
        genes = new OffsetMap<>();
        unifiedSet.forEach(g -> putGene((GeneMention) g));
    }

    private TreeSet<Span> unifySpanLongerFirst(Collection<? extends Span> spans) {
        Span otherGene = null;
        TreeSet<Span> sortedGenes = new TreeSet<>(new OffsetSpanComparator());
        for (Span gm : spans) {
            if (sortedGenes.contains(gm)) {
                continue;
            } else if (null != (otherGene = sortedGenes.floor(gm))) {
                if (otherGene.getOffsets().isOverlappedBy(gm.getOffsets())) {
                    int gmLength = gm.getOffsets().getMaximum() - gm.getOffsets().getMinimum();
                    int otherLength = otherGene.getOffsets().getMaximum() - otherGene.getOffsets().getMinimum();
                    if (gmLength > otherLength) {
                        if (sortedGenes.remove(otherGene)) {
                            sortedGenes.add(gm);
                        }
                    }
                } else {
                    sortedGenes.add(gm);
                }
            } else if (null != (otherGene = sortedGenes.ceiling(gm))) {
                if (otherGene.getOffsets().isOverlappedBy(gm.getOffsets())) {
                    int gmLength = gm.getOffsets().getMaximum() - gm.getOffsets().getMinimum();
                    int otherLength = otherGene.getOffsets().getMaximum() - otherGene.getOffsets().getMinimum();
                    if (gmLength > otherLength) {
                        if (sortedGenes.remove(otherGene)) {
                            sortedGenes.add(gm);
                        }
                    }
                } else {
                    sortedGenes.add(gm);
                }
            } else {
                sortedGenes.add(gm);
            }
        }
        return sortedGenes;
    }

    public void unifyGenesPrioritizeTagger(NavigableSet<GeneMention> sortedGenes, GeneTagger tagger) {
        allGenes.forEach(gm -> {
            GeneMention otherGene = null;
            if (sortedGenes.contains(gm)) {
                // As comparison is done via ranges, two genes are equal,
                // if they cover the same range, even if their respective other
                // values are different
                GeneTagger candidateTagger = gm.getTagger();
                if (candidateTagger == tagger) {
                    if (sortedGenes.remove(gm)) {
                        sortedGenes.add(gm);
                    }
                }
            } else if (null != (otherGene = sortedGenes.floor(gm))) {
                if (otherGene.getOffsets().isOverlappedBy(gm.getOffsets())) {
                    GeneTagger candidateTagger = gm.getTagger();
                    if (candidateTagger == tagger) {
                        if (sortedGenes.remove(otherGene)) {
                            sortedGenes.add(gm);
                        }
                    }
                } else {
                    sortedGenes.add(gm);
                }
            } else if (null != (otherGene = sortedGenes.ceiling(gm))) {
                if (otherGene.getOffsets().isOverlappedBy(gm.getOffsets())) {
                    GeneTagger candidateTagger = gm.getTagger();
                    if (candidateTagger == tagger) {
                        if (sortedGenes.remove(otherGene)) {
                            sortedGenes.add(gm);
                        }
                    }
                } else {
                    sortedGenes.add(gm);
                }
            } else {
                sortedGenes.add(gm);
            }
        });
        genes = new OffsetMap<>();
        sortedGenes.forEach(g -> putGene(g));
    }

    /**
     * Returns the raw gene mentions in this document, without any filtering,
     * unification, aggregation or whatsoever and possibly from multiple taggers.
     *
     * @return All gene mentions in this document.
     */
    public List<GeneMention> getAllGenes() {
        return allGenes;
    }

    /**
     * Adds the given gene mention into the {@link #genes} map by its offset.
     *
     * @param gm
     */
    private void putGene(GeneMention gm) {
        if (gm.getOffsets() == null)
            throw new IllegalArgumentException("The passed gene mention does not specify text offsets: " + gm);
        if (genes == null)
            genes = new OffsetMap<>();
        putGene(gm, genes);
    }

    public void putGoldGene(GeneMention gm) {
        if (gm.getOffsets() == null)
            throw new IllegalArgumentException("The passed gene mention does not specify text offsets: " + gm);
        if (goldGenes == null)
            goldGenes = new OffsetMap<>();
        putGene(gm, goldGenes);
    }

    private void putGene(GeneMention gm, OffsetMap<List<GeneMention>> geneMap) {
        assert geneMap != null;
        if (gm.getOffsets() == null)
            throw new IllegalArgumentException("The passed gene mention does not specify text offsets: " + gm);
        List<GeneMention> gmList = geneMap.get(gm.getOffsets());
        if (gmList == null) {
            gmList = new ArrayList<>();
            geneMap.put(gm.getOffsets(), gmList);
        }
        gmList.add(gm);
    }

    private void replaceGene(GeneMention gene, GeneMention replacement) {
        List<GeneMention> gmList = genes.get(gene.getOffsets());
        int index = gmList.indexOf(gene);
        gmList.set(index, replacement);
    }

    public String getCoveredText(Span span) {
        return getCoveredText(span.getOffsets());
    }

    public String getCoveredText(Range<Integer> range) {
        return getCoveredText(range.getMinimum(), range.getMaximum());
    }

    public String getCoveredText(int begin, int end) {
        return documentText.substring(begin, end);
    }

    /**
     * Adds the given GeneMention to the set of currently selected genes but not to
     * the allGenes set.
     *
     * @param gm The gene mention to add.
     */
    public void selectGene(GeneMention gm) {
        putGene(gm);
    }

    public TermNormalizer getTermNormalizer() {
        return termNormalizer;
    }

    public void setTermNormalizer(TermNormalizer termNormalizer) {
        this.termNormalizer = termNormalizer;
    }

    public void removeGene(GeneMention gm) {
        List<GeneMention> genesAtOffset = getGeneMap().get(gm.getOffsets());
        genesAtOffset.remove(gm);
        if (genesAtOffset.isEmpty())
            getGeneMap().remove(gm.getOffsets());
    }

    public AhoCorasickOptimized getGeneNameDictionary() {
        return geneNameDictionary;
    }

    /**
     * Builds an instance of {@link AhoCorasickOptimized} from the currently
     * selected genes. The instance is stored internally and can also be retrieved
     * by {@link #getGeneNameDictionary()}.
     *
     * @return A trie dictionary compiled from the names (text occurrence) of all
     * selected genes.
     */
    public AhoCorasickOptimized buildGeneNameTrie() {
        geneNameDictionary = new AhoCorasickOptimized(
                getGenes().map(GeneMention::getText).collect(Collectors.toList()));
        return geneNameDictionary;
    }

    /**
     * Merges those gene sets that are connected via acronym resolution or, for gene
     * mentions that are not covered by any acronym, merges by name.
     */
    public void agglomerateByAcronyms() {
        Collection<Acronym> docAcronyms = getAcronyms().values();
        if (docAcronyms.isEmpty()) {
            return;
        }

        // for quick access to the gene sets by GeneMention
        Map<GeneMention, GeneSet> geneSetMap = new HashMap<>();
        if (geneSets == null)
            getGeneSets();
        geneSets.stream().forEach(gs -> gs.forEach(gm -> geneSetMap.put(gm, gs)));

        Map<Range<Integer>, GeneSet> mergedSets = new HashMap<>();

        for (Acronym acronym : getAcronyms().values()) {
            Collection<GeneMention> gms = getOverlappingGenes(acronym.getOffsets()).collect(toList());
            if (gms.isEmpty())
                continue;

            String acronymText = getCoveredText(acronym);

            GeneMention gm = gms.stream().findFirst().get();
            AcronymLongform longform = acronym.getLongform();

            Collection<GeneMention> longGms = getOverlappingGenes(longform.getOffsets()).collect(toList());
            if (longGms.isEmpty())
                continue;

            GeneMention longGm = longGms.stream().findFirst().get();

            if (gm.equals(longGm))
                continue;

            // This should avoid a too lose matching between genes and acronyms. For
            // example, the acronym HLH should not taken to be the same as HLH462. But we
            // allow minor discrepancies for species prefixes.
            if (gm.getText().length() > acronymText.length() + 2 || !gm.getText().endsWith(acronymText)
                    || (gm.getText().length() != acronymText.length()
                    && !Character.isLowerCase(gm.getText().charAt(0))))
                continue;

            // Also it happens that an abbreviation's longform overlaps a gene but only in a
            // rather small part (e.g. TNF vs. type-1 tumor-necrosis-factor
            // (TNF)-receptor-associated protein (TRAP)-2). Thus we check that the full form
            // actually is the gene.
            if (longGm.getText().length() != longform.getEnd() - longform.getBegin())
                continue;

            // get the sets that are currently associated with the two genes
            GeneSet gmSet = mergedSets.get(gm.getOffsets());
            GeneSet longGmSet = mergedSets.get(longGm.getOffsets());
            if (gmSet == null)
                gmSet = geneSetMap.get(gm);
            if (longGmSet == null)
                longGmSet = geneSetMap.get(longGm);

            // We don't want to merge plural and non-plural sets since this is an import
            // part of family recognition
            if (gmSet.isPlural() ^ longGmSet.isPlural())
                continue;

            // now merge the smaller set into the larger one
            GeneSet from;
            GeneSet to;
            if (gmSet.size() > longGmSet.size()) {
                from = longGmSet;
                to = gmSet;
            } else {
                from = gmSet;
                to = longGmSet;
            }

            // may happen if we have overlapping / embedded acronyms (e.g. human
            // follicle stimulating hormone receptor (hFSH-R) has the acronyms
            // hFSH-R and FSH-R)
            if (from == to)
                continue;

            to.addAll(from);
            from.clear();

            mergedSets.put(longGm.getOffsets(), to);
            mergedSets.put(gm.getOffsets(), to);
            geneSetMap.remove(longGm);
            geneSetMap.remove(gm);
            // the next two lines only work because hashCode() and equals() in
            // GeneSet have been overwritten to work with the
            // System.identityHashCode
            geneSets.remove(longGmSet);
            geneSets.remove(gmSet);
        }

        geneSets.addAll(mergedSets.values());
    }

    public void agglomerateByNames() {
        // for quick access to the gene sets by gene text
        Map<String, GeneSet> geneSetMap = new HashMap<>();
        if (geneSets == null)
            getGeneSets();
        geneSets.stream().forEach(gs -> gs.forEach(gm -> geneSetMap.put(gm.getText(), gs)));

        List<GeneSet> geneSetList = new ArrayList<>(geneSets);
        for (int i = 0; i < geneSetList.size() - 1; i++) {
            for (int j = i + 1; j < geneSetList.size(); j++) {
                GeneSet iSet = geneSetList.get(i);
                GeneSet jSet = geneSetList.get(j);

                // We don't want to merge plural and non-plural sets since this is an import
                // part of family recognition
                if (iSet.isPlural() ^ jSet.isPlural())
                    continue;

                // Check if there are common names in both sets
                if (!Sets.intersection(iSet.stream().map(GeneMention::getText).collect(toSet()),
                        jSet.stream().map(GeneMention::getText).collect(toSet())).isEmpty()) {

                    iSet.addAll(jSet);
                    jSet.clear();

                    // the next line only work because hashCode() and equals() in
                    // GeneSet have been overwritten to work with the
                    // System.identityHashCode
                    geneSets.remove(jSet);
                }
            }
        }

    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GeneDocument that = (GeneDocument) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {

        return Objects.hash(id);
    }

    public void setSpeciesMeshHeadings(Collection<MeshHeading> meshHeadings) {
        for (MeshHeading heading : meshHeadings) {
            final String name = heading.getHeading();
            final String[] split = name.split(",\\s+");
            for (String s : split) {
                final List<String> taxIds = meshHeadings2TaxId.get(s.trim());
                if (taxIds != null) {
                    for (String taxId : taxIds) {
                        heading.addTaxonomyId(taxId);
                    }
                }
            }
        }
    }

    public Collection<MeshHeading> getMeshHeadings() {
        return meshHeadings != null ? meshHeadings : Collections.emptyList();
    }

    public void setMeshHeadings(Collection<MeshHeading> meshHeadings) {
        this.meshHeadings = meshHeadings;
        setSpeciesMeshHeadings(meshHeadings);
    }

    public Stream<GeneMention> getGenesWithText(String text) {
        return getGenes().filter(gm -> gm.getText().equals(text));
    }

    public String getDefaultSpecies() {
        return defaultSpecies;
    }

    public void setDefaultSpecies(String defaultSpecies) {
        this.defaultSpecies = defaultSpecies;
    }

    public Entry<Range<Integer>, SpeciesMention> getNearestPreviousSpeciesMention(Range<Integer> range, String taxId) {
        final OffsetMap<SpeciesMention> textCandidates = species.getTextCandidates();
        Map.Entry<Range<Integer>, SpeciesMention> lower = textCandidates.lowerEntry(range);
        while (lower != null && !lower.getValue().getTaxId().equals(taxId)) {
            lower = textCandidates.lowerEntry(lower.getKey());
        }
        if (lower != null && !lower.getValue().getTaxId().equals(taxId))
            lower = null;

        return lower;
    }

    public Entry<Range<Integer>, SpeciesMention> getNearestNextSpeciesMention(Range<Integer> range, String taxId) {
        final OffsetMap<SpeciesMention> textCandidates = species.getTextCandidates();
        Map.Entry<Range<Integer>, SpeciesMention> higher = textCandidates.higherEntry(range);
        while (higher != null && !higher.getValue().getTaxId().equals(taxId)) {
            higher = textCandidates.higherEntry(higher.getKey());
        }
        if (higher != null && !higher.getValue().getTaxId().equals(taxId))
            higher = null;
        return higher;
    }
}