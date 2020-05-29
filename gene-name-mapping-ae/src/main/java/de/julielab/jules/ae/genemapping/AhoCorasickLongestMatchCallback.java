package de.julielab.jules.ae.genemapping;

import com.lahodiuk.ahocorasick.AhoCorasickOptimized.MatchCallback;
import org.apache.commons.lang3.Range;

import java.util.TreeMap;

/**
 * To be used with an instance of {@link com.lahodiuk.ahocorasick.AhoCorasickOptimized}. Collects all matches
 * made by {@link com.lahodiuk.ahocorasick.AhoCorasickOptimized#match(String, MatchCallback)} but only keeps
 * the longest match in case of overlapping matches.
 */
public class AhoCorasickLongestMatchCallback implements MatchCallback {

    // The comparator of this maps says that all overlapping ranges are equal.
    private TreeMap<Range<Integer>, String> longestMatches = new TreeMap<>((r1, r2) -> {
        if (r1.isOverlappedBy(r2)) return 0;
        return r1.getMinimum() < r2.getMinimum() ? -1 : 1;
    });


    @Override
    public void onMatch(int startPosition, int endPosition, String matched) {
        Range<Integer> range = Range.between(startPosition, endPosition);
        // Get the "largest range that is lower or equal to the input range". Since the comparator we use
        // for the treemap interprets overlapping as equality, we will get an overlapping match if it exists.
        final Range<Integer> floor = longestMatches.floorKey(range);

        // If there is an overlapping match, check if the old or the new match cover a larger span.
        if (floor != null && floor.isOverlappedBy(range)) {
            if (range.getMaximum() - range.getMinimum() > floor.getMaximum() - floor.getMinimum()) {
                longestMatches.remove(floor);
                longestMatches.put(range, matched);
            }
        } else {
            // If there is no conflict, just add the new match.
            longestMatches.put(range, matched);
        }
    }

    public TreeMap<Range<Integer>, String> getLongestMatches() {
        return longestMatches;
    }

    public void clear() {
        longestMatches.clear();
    }
}
