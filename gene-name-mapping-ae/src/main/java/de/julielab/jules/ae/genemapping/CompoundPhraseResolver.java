package de.julielab.jules.ae.genemapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;

import de.julielab.jules.ae.genemapping.genemodel.Acronym;
import de.julielab.jules.ae.genemapping.genemodel.AcronymLongform;
import de.julielab.jules.ae.genemapping.genemodel.GeneDocument;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.genemodel.PosTag;
import de.julielab.jules.ae.genemapping.utils.GeneCandidateRetrievalException;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;

public class CompoundPhraseResolver {
    private Matcher punctM;
    private Matcher singleBaseRangeM;
    private Matcher doubleBaseRangeM;
    private Matcher singleBaseEnumM;
    private Matcher doubleBaseEnumM;
    private LuceneCandidateRetrieval luceneCandidateRetrieval;

    public CompoundPhraseResolver(GeneMappingConfiguration config) throws GeneMappingException {
        this();
        luceneCandidateRetrieval = new LuceneCandidateRetrieval(config);
    }

    public CompoundPhraseResolver() {
        punctM = Pattern.compile("^\\p{P}").matcher("");
        singleBaseRangeM = Pattern.compile("(.*?)(\\s*)([0-9]+)\\s*-\\s*([0-9]+)").matcher("");
        doubleBaseRangeM = Pattern.compile("(.*?)(\\s*)([0-9]+)\\s*-\\s*\\1\\s*([0-9]+)").matcher("");
        singleBaseEnumM = Pattern.compile("(.*?)(\\s*)([0-9]+)((\\s*/\\s*[0-9]+)+)").matcher("");
        doubleBaseEnumM = Pattern.compile("(.*?)(\\s*)([0-9]+)((\\s*/\\s*\\1\\s*[0-9]+)+)").matcher("");
    }

    public void resolve(GeneDocument document, boolean keepOriginals) {
        //resolveAcronymsInGenes(document);
        resolveConjunctionsInGenes(document, keepOriginals);
        resolveGeneRanges(document, keepOriginals);
        //resolveEnumerations(document);
//		splitGenesAtFinalAcronyms(document);
    }

    private void splitGenesAtFinalAcronyms(GeneDocument document) {
        List<GeneMention> remove = new ArrayList<>();
        for (GeneMention gm : document.getGenesIterable()) {
            Optional<Acronym> acroO = document.getOverlappingAcronyms(gm.getOffsets()).stream().findFirst();
            if (!acroO.isPresent())
                continue;
            Acronym acro = acroO.get();
            if (acro.getOffsets().getMaximum() - acro.getOffsets().getMinimum() >= gm.getOffsets().getMaximum() - gm.getOffsets().getMinimum())
                continue;
            if (acro.getBegin() == gm.getBegin() || acro.getEnd() == gm.getEnd()) {
                GeneMention remainderGene = new GeneMention(gm);
                int begin = acro.getBegin() == gm.getBegin() ? acro.getEnd() : gm.getBegin();
                int end = acro.getEnd() == gm.getEnd() ? acro.getBegin() : gm.getEnd();
                System.out.println("Gene: -" + gm.getText() + "-");
                System.out.println("Acronym: -" + document.getCoveredText(acro) + "-");
                System.out.println(document.getCoveredText(acro).equals(gm.getText()));
                remainderGene.setText(document.getCoveredText(begin, end).trim());
                remainderGene.setParent(gm);

                GeneMention acroGene = new GeneMention(gm);
                acroGene.setText(document.getCoveredText(acro));
                acroGene.setParent(gm);
                try {
                    List<SynHit> candidates = luceneCandidateRetrieval.getCandidates(remainderGene);
                    if (!candidates.isEmpty() || candidates.get(0).isExactMatch())
                        continue;
                    List<SynHit> remainderCandidates = luceneCandidateRetrieval.getCandidates(remainderGene);
                    if (remainderCandidates.isEmpty() || !remainderCandidates.get(0).isExactMatch())
                        continue;
                    List<SynHit> acroCandidates = luceneCandidateRetrieval.getCandidates(acroGene);
                    if (acroCandidates.isEmpty() || !acroCandidates.get(0).isExactMatch())
                        continue;
                } catch (GeneCandidateRetrievalException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                document.selectGene(remainderGene);

                document.selectGene(acroGene);


//				System.out.println(gm.getText());
//				System.out.println(acroGene.getText());
//				System.out.println(remainderGene.getText());

                remove.add(gm);
            }
        }
        remove.forEach(document::removeGene);
    }

    public void resolveEnumerations(GeneDocument document) {
        List<GeneMention> remove = new ArrayList<>();
        for (GeneMention gm : document.getGenesIterable()) {
            String text = gm.getText();
            singleBaseEnumM.reset(text);
            Matcher matchingMatcher = null;
            if (singleBaseEnumM.matches()) {
                matchingMatcher = singleBaseEnumM;
            } else {
                doubleBaseEnumM.reset(text);
                if (doubleBaseEnumM.matches())
                    matchingMatcher = doubleBaseEnumM;
            }
            if (matchingMatcher != null) {
                String base = matchingMatcher.group(1);
                String ws = matchingMatcher.group(2);
                List<String> specifiers = new ArrayList<>();
                // If we have double (or actually "multiple") base forms, the
                // specifiers actually already have the base form included
                if (matchingMatcher == singleBaseEnumM)
                    specifiers.add(matchingMatcher.group(3));
                else
                    // For single base expressions we prepend the base
                    specifiers.add(base + ws + matchingMatcher.group(3));
                specifiers.addAll(Stream.of(matchingMatcher.group(4).split("/")).filter(s -> !s.trim().isEmpty())
                        .collect(Collectors.toList()));
                for (String specifier : specifiers) {
                    GeneMention newGm = new GeneMention(gm);
                    if (matchingMatcher == singleBaseEnumM)
                        newGm.setText((base + ws + specifier).trim());
                    else
                        newGm.setText(specifier.trim());
                    newGm.setParent(gm);
                    newGm.addTaggingModifier(getClass().getSimpleName() + " enumeration resolution "
                            + (matchingMatcher == singleBaseEnumM ? "single base" : "multi base"));
                    document.selectGene(newGm);
                }
                remove.add(gm);
            }
        }
        remove.forEach(document::removeGene);
    }

    public void resolveGeneRanges(GeneDocument document, boolean keepOriginals) {
        List<GeneMention> remove = new ArrayList<>();
        for (GeneMention gm : document.getGenesIterable()) {
            String text = gm.getText();
            singleBaseRangeM.reset(text);
            Matcher matchingMatcher = null;
            if (singleBaseRangeM.matches()) {
                matchingMatcher = singleBaseRangeM;
            } else {
                doubleBaseRangeM.reset(text);
                if (doubleBaseRangeM.matches())
                    matchingMatcher = doubleBaseRangeM;
            }
            if (matchingMatcher != null) {
                String base = matchingMatcher.group(1);
                String ws = matchingMatcher.group(2);
                Integer from = Integer.parseInt(matchingMatcher.group(3));
                Integer to = Integer.parseInt(matchingMatcher.group(4));
                for (int i = from; i <= to; ++i) {
                    GeneMention newGm = new GeneMention(gm);
                    newGm.setText(base + ws + i);
                    newGm.setParent(gm);
                    newGm.addTaggingModifier(getClass().getSimpleName() + " gene range resolution");
                    document.selectGene(newGm);
                }
                remove.add(gm);
            }
        }
        if (!keepOriginals)
            remove.forEach(document::removeGene);
    }

    public void resolveConjunctionsInGenes(GeneDocument document, boolean keepOriginals) {
        List<GeneMention> remove = new ArrayList<>();
        for (GeneMention gm : document.getGenesIterable()) {
            List<PosTag> rawPos = gm.getPosTags();
            if (rawPos == null)
                rawPos = new ArrayList<>(document.getOverlappingPosTags(gm.getOffsets()));
            List<PosTag> pos = new ArrayList<>();
            for (Iterator<PosTag> posIt = rawPos.iterator(); posIt.hasNext(); ) {
                PosTag posTag = posIt.next();
                String text = document.getCoveredText(posTag);
                List<String> singleToken = new ArrayList<>();
                singleToken.add(text);
                singleToken = gm.getGeneName().getNormalizer().splitAwayRomanNumbers(singleToken);
                text = gm.getGeneName().getNormalizer().splitAwayRomanNumbers(singleToken).stream().collect(Collectors.joining(" "));
                String[] split = gm.getGeneName().getNormalizer().normalize(text).split(" ");
                int begin = posTag.getBegin();
                for (int i = 0; i < split.length; i++) {
                    String token = split[i];
                    String newPosTag;
                    if (token.matches("[0-9]+"))
                        newPosTag = "CD";
                    else if (token.matches(CandidateFilter.GREEK_REGEX))
                        newPosTag = "SYM";
                    else
                        newPosTag = posTag.getTag();
                    PosTag newTag = new PosTag(newPosTag, Range.between(begin, begin + token.length()));
                    pos.add(newTag);
                    begin = newTag.getEnd();
                }
            }
            // We need at least four elements: the base name, the first
            // specifier, the conjunction and the second specifier
            if (pos.size() < 4)
                continue;
            List<String> tags = pos.stream().map(p -> p.getTag()).collect(Collectors.toList());
            int conjIndex = tags.indexOf("CC");
            // If there is no conjunction we continue to the next gene
            if (conjIndex < 0)
                continue;
            // We also can't do anything about a conjunction at the beginning or
            // at the end of the mention
            if (conjIndex == 0 || conjIndex == pos.size() - 1)
                continue;

            Function<List<Integer>, String> textFunc = list -> list.stream()
                    .map(i -> document.getCoveredText(pos.get(i))).collect(Collectors.joining(" "));

            boolean leftSpecs = conjIndex < tags.size() / 2 ? true : false;

            // First, get the obvious specifiers: The one at the very beginning
            // or very end of the gene mention...
            List<List<Integer>> specs = new ArrayList<>();
            {
                if (leftSpecs) {
                    specs.add(IntStream.range(0, conjIndex).mapToObj(Integer::new).collect(Collectors.toList()));
                } else {
                    specs.add(IntStream.range(conjIndex + 1, pos.size()).mapToObj(Integer::new)
                            .collect(Collectors.toList()));
                }
                int lastComma = -1;
                // ... and the ones between commas
                for (int i = 0; i < pos.size(); i++) {
                    String tag = tags.get(i);
                    if (tag.equals(",")) {
                        if (lastComma != -1) {
                            specs.add(IntStream.range(lastComma + 1, i).mapToObj(Integer::new)
                                    .collect(Collectors.toList()));
                        } else {
                            lastComma = i;
                        }
                    }
                }
            }
            // The only specifier that is really hard is the one adjacent to the
            // base form
            // We collect the PoS tags from the easy-to-recognize specifiers and
            // use them as a guidance to what could form the last remaining specifier
            Set<String> eligibleSpecifierTags = specs.stream().flatMap(s -> s.stream()).map(i -> pos.get(i).getTag())
                    .collect(Collectors.toSet());
            List<Integer> baseAdjacentSpec;
            if (leftSpecs) {
                int lastComma = tags.lastIndexOf(",");
                int beginIndex = lastComma > conjIndex ? lastComma : conjIndex;
                // move to the beginning of the last specifier
                ++beginIndex;
                int endIndex = beginIndex;
                while (endIndex < tags.size() && eligibleSpecifierTags.contains(tags.get(endIndex))) {
                    ++endIndex;
                }
                baseAdjacentSpec = IntStream.range(beginIndex, endIndex).mapToObj(Integer::new)
                        .collect(Collectors.toList());
            } else {
                int firstComma = tags.indexOf(",");
                int endIndex = firstComma < conjIndex && firstComma != -1 ? firstComma : conjIndex;
                // move to the beginning of the last specifier
                int beginIndex = endIndex - 1;
                while (beginIndex >= 0) {
                    if (!eligibleSpecifierTags.contains(tags.get(beginIndex))) {
                        ++beginIndex;
                        break;
                    }
                    --beginIndex;
                }
                if (beginIndex < 0)
                    ++beginIndex;
                baseAdjacentSpec = IntStream.range(beginIndex, endIndex).mapToObj(Integer::new)
                        .collect(Collectors.toList());
            }

            baseAdjacentSpec = findFittingSpecSequence(baseAdjacentSpec, specs, tags, leftSpecs);

            if (baseAdjacentSpec.isEmpty())
                continue;

            specs.add(baseAdjacentSpec);

            boolean emptySpec = false;
            for (List<Integer> specifier : specs) {
                if (specifier.isEmpty())
                    emptySpec = true;
            }
            if (emptySpec)
                continue;

            remove.add(gm);
            List<Integer> base;
            if (leftSpecs) {
                Integer maxSpecEnd = specs.stream().map(l -> l.get(l.size() - 1)).max(Integer::compareTo)
                        .get();
                base = IntStream.range(maxSpecEnd + 1, pos.size()).mapToObj(Integer::new).collect(Collectors.toList());
                for (List<Integer> specifier : specs) {
                    List<Integer> newGeneList = new ArrayList<>(specifier);
                    newGeneList.addAll(base);
                    GeneMention newGene = new GeneMention(gm);
                    newGene.setText(textFunc.apply(newGeneList));
                    newGene.setParent(gm);
                    newGene.addTaggingModifier(getClass().getSimpleName() + " conjunction resolution");
                    document.selectGene(newGene);
                }
            } else {
                Integer minSpecBegin = specs.stream().map(l -> l.get(0)).min(Integer::compareTo).get();
                base = IntStream.range(0, minSpecBegin).mapToObj(Integer::new).collect(Collectors.toList());
                for (List<Integer> specifier : specs) {
                    List<Integer> newGeneList = new ArrayList<>(base);
                    newGeneList.addAll(specifier);
                    GeneMention newGene = new GeneMention(gm);
                    newGene.setText(textFunc.apply(newGeneList));
                    newGene.setParent(gm);
                    newGene.addTaggingModifier(getClass().getSimpleName() + " conjunction resolution");
                    document.selectGene(newGene);
                }
            }

        }
        if (!keepOriginals) {
            for (GeneMention gm : remove) {
                document.removeGene(gm);
            }
        }
    }

    private List<Integer> findFittingSpecSequence(List<Integer> baseAdjacentSpec, List<List<Integer>> specs,
                                                  List<String> tags, boolean leftSpecs) {
        List<List<Integer>> descendingSizeSpecs = specs.stream().sorted((l1, l2) -> l2.size() - l1.size())
                .collect(Collectors.toList());
        List<Integer> rawSequence = new ArrayList<>(baseAdjacentSpec);
        List<Integer> foundSequence = Collections.emptyList();
        if (leftSpecs)
            rawSequence.sort((i1, i2) -> i2 - i1);
        // This loop compares all easy specifiers with the base sequence that
        // still contains the specifier that is adjacent to the base form
        for (List<Integer> spec : descendingSizeSpecs) {
            List<String> specTags;
            if (leftSpecs)
                specTags = spec.stream().sorted((i1, i2) -> i2 - i1).map(i -> tags.get(i)).collect(Collectors.toList());
            else
                specTags = spec.stream().map(i -> tags.get(i)).collect(Collectors.toList());
            List<String> baseAdjSpecTags = rawSequence.stream().map(i -> tags.get(i)).collect(Collectors.toList());
            int k = specTags.size();
            int currentMatchLength = 0;
            // This loop successively shortens the current comparison sequence
            while (k > 0) {
                --k;
                int j = rawSequence.size() - 1;
                currentMatchLength = 0;
                // This loop checks if for the current sequence lengths we find
                // a match
                while (j >= 0 && k >= 0) {
                    if (!baseAdjSpecTags.get(j).equals(specTags.get(k))) {
                        break;
                    }
                    --j;
                    --k;
                    ++currentMatchLength;
                }
                if (currentMatchLength > 0) {
                    List<Integer> sequence = rawSequence.subList(j + 1, rawSequence.size());
                    if (sequence.size() > foundSequence.size())
                        foundSequence = sequence;
                }
            }
        }
        if (leftSpecs && !foundSequence.isEmpty())
            foundSequence.sort((i1, i2) -> i1 - i2);
        return foundSequence;
    }

    public void resolveAcronymsInGenes(GeneDocument document) {
        for (AcronymLongform al : document.getAcronymLongforms().values()) {
            // We search for gene mentions that span an acronym longform and
            // their acronym
            Optional<GeneMention> geneO = document.getOverlappingGenes(al.getOffsets()).findFirst();
            if (!geneO.isPresent())
                continue;
            GeneMention gene = geneO.get();
            Optional<Acronym> acroO = document.getOverlappingAcronyms(gene.getOffsets()).stream()
                    .filter(a -> a.getLongform().equals(al)).findFirst();
            if (!acroO.isPresent())
                continue;
            Acronym acronym = acroO.get();
            // We have something like "leukotriene B4 (LTB4) omega-hydroxylase"
            // or "interleukin (IL)-12".
            // Create two new genes: interleukin 12 and IL-12.
            // Get the pre- and post acronym string found within the gene
            // mention
            // -1 because we want to omit the opening parenthesis of the acronym
            Range<Integer> preAcroRange = Range.between(gene.getBegin(), acronym.getBegin() - 1);
            String preAcroGene = document.getCoveredText(preAcroRange).trim();
            // +1 because we want to omit the closing parenthesis of the acronym
            Range<Integer> postAcroRange = Range.between(acronym.getEnd() + 1, gene.getEnd());
            String postAcroGene = document.getCoveredText(postAcroRange).trim();

            // Create new gene string by concatenating the suffix to the
            // longform gene and acronym gene text (we don't use the actual
            // acronym longform by intention, it seems the gene tagger knows
            // better where the mention actually started).
            punctM.reset(postAcroGene);
            // For the long form, remove punctuation at the beginning of the
            // suffix
            String longGeneText = StringUtils.normalizeSpace(preAcroGene + " " + punctM.replaceFirst(""));
            // For the short form, retain punctuation. If there is none, add a
            // whitespace
            punctM.reset();
            String space = punctM.find() ? "" : " ";
            String shortGeneText = document.getCoveredText(acronym) + space + postAcroGene;
            // Set the new text to the original gene. It is important that we
            // later look for candidates for the set gene text, not the covered
            // document text.
            // System.out.println("Original: " + gene.getText());
            gene.setText(longGeneText);
            List<PosTag> longPosTags = new ArrayList<>(document.getOverlappingPosTags(preAcroRange));
            longPosTags.addAll(document.getOverlappingPosTags(postAcroRange));
            gene.setPosTags(longPosTags);
            gene.addTaggingModifier(getClass().getSimpleName() + " acronym resolution");
            // System.out.println("Original changed to: " + gene.getText());

            GeneMention shortGene = new GeneMention(gene);
            shortGene.setParent(gene);
            shortGene.setText(shortGeneText);
            // shortGene.setOffsets(Range.between(acronym.getBegin(),
            // gene.getEnd()));
            List<PosTag> shortPosTags = new ArrayList<>(document.getOverlappingPosTags(acronym.getOffsets()));
            shortPosTags.addAll(document.getOverlappingPosTags(postAcroRange));
            shortGene.setPosTags(shortPosTags);
            shortGene.addTaggingModifier(getClass().getSimpleName() + " acronym resolution (newly created)");

            // System.out.println("New: " + shortGene.getText());

            document.selectGene(shortGene);
        }
    }

    /**
     * This is for development and debugging.
     *
     * @param document
     */
    public void printComplexPhrases(GeneDocument document) {
        for (PosTag conj : document.getPosTags().values().stream().filter(p -> p.getTag().equals("CC"))
                .collect(Collectors.toList())) {
            Range<Integer> sentence = document.getovappingSentence(conj);
            List<Entry<Range<Integer>, String>> previousChunks = new ArrayList<>();
            List<Entry<Range<Integer>, String>> nextChunks = new ArrayList<>();
            int window = 2;
            Range<Integer> currentOffset = conj.getOffsets();
            for (int i = 0; i < window; ++i) {
                Entry<Range<Integer>, String> chunk = document.getChunks().lowerEntry(currentOffset);
                if (chunk == null)
                    continue;
                // stay within sentence boundaries
                if (!sentence.isOverlappedBy(chunk.getKey()))
                    continue;
                previousChunks.add(chunk);
                currentOffset = chunk.getKey();
            }
            Collections.reverse(previousChunks);
            currentOffset = conj.getOffsets();
            for (int i = 0; i < window; ++i) {
                Entry<Range<Integer>, String> chunk = document.getChunks().higherEntry(currentOffset);
                if (chunk == null)
                    continue;
                // stay within sentence boundaries
                if (!sentence.isOverlappedBy(chunk.getKey()))
                    continue;
                nextChunks.add(chunk);
                currentOffset = chunk.getKey();
            }
            System.out.println();
            Range<Integer> previousChunksRange = previousChunks.size() > 0
                    ? Range.between(previousChunks.get(0).getKey().getMinimum(),
                    previousChunks.get(previousChunks.size() - 1).getKey().getMaximum())
                    : Range.between(conj.getBegin(), conj.getBegin());
            Range<Integer> nextChunksRange = nextChunks.size() > 0
                    ? Range.between(nextChunks.get(0).getKey().getMinimum(),
                    nextChunks.get(nextChunks.size() - 1).getKey().getMaximum())
                    : Range.between(conj.getEnd(), conj.getEnd());
            List<GeneMention> previousGenes = document.getOverlappingGenes(previousChunksRange)
                    .collect(Collectors.toList());
            List<GeneMention> nextGenes = document.getOverlappingGenes(nextChunksRange).collect(Collectors.toList());
            if (previousGenes.isEmpty() || nextGenes.isEmpty())
                continue;
            System.out.println(document.getId());
            System.out.println("Previous genes:");
            for (GeneMention g : previousGenes)
                System.out.println(document.getCoveredText(g));
            System.out.println("--");
            int leftBorder = previousChunksRange.getMinimum();
            int rightBorder = nextChunksRange.getMaximum();
            System.out.println(document.getCoveredText(leftBorder, rightBorder));
            for (Entry<Range<Integer>, String> chunk : previousChunks)
                System.out.print(document.getCoveredText(chunk.getKey()) + " ");
            System.out.print("--" + document.getCoveredText(conj) + "-- ");
            for (Entry<Range<Integer>, String> chunk : nextChunks)
                System.out.print(document.getCoveredText(chunk.getKey()) + " ");
            System.out.println("\n--\nNext genes:");
            for (GeneMention g : nextGenes)
                System.out.println(document.getCoveredText(g));
        }
    }
}
