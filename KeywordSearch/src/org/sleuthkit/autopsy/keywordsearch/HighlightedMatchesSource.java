/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.keywordsearch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.sleuthkit.autopsy.datamodel.HighlightLookup;
import org.sleuthkit.autopsy.keywordsearch.KeywordQueryFilter.FilterType;
import org.sleuthkit.datamodel.Content;

/**
 * Gets extracted content from Solr with the parts that match the query
 * highlighted
 */
class HighlightedMatchesSource implements MarkupSource, HighlightLookup {

    private static final Logger logger = Logger.getLogger(HighlightedMatchesSource.class.getName());
    private static final String HIGHLIGHT_PRE = "<span style='background:yellow'>";
    private static final String HIGHLIGHT_POST = "</span>";
    private static final String ANCHOR_PREFIX = HighlightedMatchesSource.class.getName() + "_";
    private static final String NO_MATCHES = "<span style='background:red'>No matches in content.</span>";
    private Content content;
    private String keywordHitQuery;
    private Server solrServer;
    private int numberPages;
    private int currentPage;
    private boolean isRegex = false;
    private boolean group = true;
    private boolean hasChunks = false;
    //stores all pages/chunks that have hits as key, and number of hits as a value, or 0 if yet unknown
    private LinkedHashMap<Integer, Integer> hitsPages;
    //stored page num -> current hit number mapping
    private HashMap<Integer, Integer> pagesToHits;
    private List<Integer> pages;
    private Map<String, List<ContentHit>> hits = null; //original hits that may get passed in
    private String originalQuery = null; //or original query if hits are not available
    private boolean inited = false;

    HighlightedMatchesSource(Content content, String keywordHitQuery, boolean isRegex) {
        this.content = content;
        this.keywordHitQuery = keywordHitQuery;
        this.isRegex = isRegex;
        this.group = true;
        this.hitsPages = new LinkedHashMap<Integer, Integer>();
        this.pages = new ArrayList<Integer>();
        this.pagesToHits = new HashMap<Integer, Integer>();

        this.solrServer = KeywordSearch.getServer();
        this.numberPages = 0;
        this.currentPage = 0;
        //hits are unknown

    }

    //when the results are not known and need to requery to get hits
    HighlightedMatchesSource(Content content, String solrQuery, boolean isRegex, String originalQuery) {
        this(content, solrQuery, isRegex);
        this.originalQuery = originalQuery;
    }

    HighlightedMatchesSource(Content content, String solrQuery, boolean isRegex, Map<String, List<ContentHit>> hits) {
        this(content, solrQuery, isRegex);
        this.hits = hits;
    }

    HighlightedMatchesSource(Content content, String solrQuery, boolean isRegex, boolean group, Map<String, List<ContentHit>> hits) {
        this(content, solrQuery, isRegex, hits);
        this.group = group;
    }

    private void init() {
        if (inited) {
            return;
        }
        try {
            this.numberPages = solrServer.queryNumFileChunks(content.getId());
        } catch (SolrServerException ex) {
            logger.log(Level.WARNING, "Could not get number pages for content: " + content.getId());
            return;
        } catch (NoOpenCoreException ex) {
            logger.log(Level.WARNING, "Could not get number pages for content: " + content.getId());
            return;
        }

        if (this.numberPages == 0) {
            hasChunks = false;
        } else {
            hasChunks = true;
        }

        //if has chunks, get pages with hits
        if (hasChunks) {
            //extract pages of interest, sorted
            final long contentId = content.getId();
            
            if (hits == null) {
                //special case, aka in case of dir tree, we don't know which chunks
                //reperform search query for the content to get matching chunks info
                KeywordSearchQuery chunksQuery = null;
                
                /**
                Keyword keywordQuery = new Keyword(this.originalQuery, !isRegex);
                if (this.isRegex)
                {
                    chunksQuery = new TermComponentQuery(keywordQuery);
                } else {
                    chunksQuery = new LuceneQuery(keywordQuery);
                    chunksQuery.escape();
                }
                 */
                String queryStr = KeywordSearchUtil.escapeLuceneQuery(this.keywordHitQuery, true, false);
                if (isRegex) {
                    //use white-space sep. field to get exact matches only of regex query result
                    queryStr = Server.Schema.CONTENT_WS + ":" + "\"" + queryStr + "\"";
                }
                Keyword keywordQuery = new Keyword(queryStr, false);
                chunksQuery = new LuceneQuery(keywordQuery);
                KeywordQueryFilter contentIdFilter = new KeywordQueryFilter(FilterType.CHUNK, contentId);
                chunksQuery.setFilter(contentIdFilter);
                try {
                    hits = chunksQuery.performQuery();
                } catch (NoOpenCoreException ex) {
                    logger.log(Level.INFO, "Could not get chunk info and get highlights", ex);
                    return;
                }
            }

            //organize the hits by page, filter as needed
            TreeSet<Integer> pagesSorted = new TreeSet<Integer>();
            for (Collection<ContentHit> hitCol : hits.values()) {
                for (ContentHit hit : hitCol) {
                    int chunkID = hit.getChunkId();
                    if (chunkID != 0 && contentId == hit.getId()) {
                        pagesSorted.add(chunkID);
                    }
                }
            }

            //set page to first page having highlights
            if (pagesSorted.isEmpty())
                this.currentPage = 0;
            else this.currentPage = pagesSorted.first();

            for (Integer page : pagesSorted) {
                hitsPages.put(page, 0); //unknown number of matches in the page
                pages.add(page);
                pagesToHits.put(page, 0); //set current hit to 0th
            }

        } else {
            //no chunks
            this.numberPages = 1;
            this.currentPage = 1;
            hitsPages.put(1, 0);
            pages.add(1);
            pagesToHits.put(1, 0);

        }

        inited = true;
    }

    //constructor for dummy singleton factory instance for Lookup
    private HighlightedMatchesSource() {
    }

    @Override
    public int getNumberPages() {
        return this.numberPages;
        //return number of pages that have hits
        //return this.hitsPages.keySet().size();
    }

    @Override
    public int getCurrentPage() {
        return this.currentPage;
    }

    @Override
    public boolean hasNextPage() {
        final int numPages = pages.size();
        int idx = pages.indexOf(this.currentPage);
        return idx < numPages - 1;

    }

    @Override
    public boolean hasPreviousPage() {
        int idx = pages.indexOf(this.currentPage);
        return idx > 0;

    }

    @Override
    public int nextPage() {
        if (!hasNextPage()) {
            throw new IllegalStateException("No next page.");
        }
        int idx = pages.indexOf(this.currentPage);
        currentPage = pages.get(idx + 1);
        return currentPage;
    }

    @Override
    public int previousPage() {
        if (!hasPreviousPage()) {
            throw new IllegalStateException("No previous page.");
        }
        int idx = pages.indexOf(this.currentPage);
        currentPage = pages.get(idx - 1);
        return currentPage;
    }

    @Override
    public boolean hasNextItem() {
        if (!this.pagesToHits.containsKey(currentPage))
            return false;
        return this.pagesToHits.get(currentPage) < this.hitsPages.get(currentPage);
    }

    @Override
    public boolean hasPreviousItem() {
        if (!this.pagesToHits.containsKey(currentPage))
            return false;
        return this.pagesToHits.get(currentPage) > 1;
    }

    @Override
    public int nextItem() {
        if (!hasNextItem()) {
            throw new IllegalStateException("No next item.");
        }
        int cur = pagesToHits.get(currentPage) + 1;
        pagesToHits.put(currentPage, cur);
        return cur;
    }

    @Override
    public int previousItem() {
        if (!hasPreviousItem()) {
            throw new IllegalStateException("No previous item.");
        }
        int cur = pagesToHits.get(currentPage) - 1;
        pagesToHits.put(currentPage, cur);
        return cur;
    }

    @Override
    public int currentItem() {
        if (!this.pagesToHits.containsKey(currentPage))
            return 0;
        return pagesToHits.get(currentPage);
    }

    @Override
    public LinkedHashMap<Integer, Integer> getHitsPages() {
        return this.hitsPages;
    }

    @Override
    public String getMarkup() {
        init(); //inits once

        String highLightField = null;

        String highlightQuery = keywordHitQuery;

        if (isRegex) {
            highLightField = LuceneQuery.HIGHLIGHT_FIELD_REGEX;
            //escape special lucene chars if not already escaped (if not a compound query)
            //TODO a better way to mark it a compound highlight query
            final String findSubstr = LuceneQuery.HIGHLIGHT_FIELD_REGEX + ":";
            if (!highlightQuery.contains(findSubstr)) {
                highlightQuery = KeywordSearchUtil.escapeLuceneQuery(highlightQuery, true, false);
            }
        } else {
            highLightField = LuceneQuery.HIGHLIGHT_FIELD_LITERAL;
            //escape special lucene chars always for literal queries query
            highlightQuery = KeywordSearchUtil.escapeLuceneQuery(highlightQuery, true, false);
        }

        SolrQuery q = new SolrQuery();

        if (isRegex) {
            StringBuilder sb = new StringBuilder();
            sb.append(highLightField).append(":");
            if (group) {
                sb.append("\"");
            }
            sb.append(highlightQuery);
            if (group) {
                sb.append("\"");
            }
            q.setQuery(sb.toString());
        } else {
            //use default field, simplifies query
            //quote only if user supplies quotes
            q.setQuery(highlightQuery);
        }

        //if (isRegex)
        //  q.setQuery(highLightField + ":" + highlightQuery); 
        //else q.setQuery(highlightQuery); //use default field, simplifies query

        final long contentId = content.getId();

        String contentIdStr = Long.toString(contentId);
        if (hasChunks) {
            contentIdStr += "_" + Integer.toString(this.currentPage);
        }

        final String filterQuery = Server.Schema.ID.toString() + ":" + contentIdStr;
        q.addFilterQuery(filterQuery);
        q.addHighlightField(highLightField); //for exact highlighting, try content_ws field (with stored="true" in Solr schema)
        q.setHighlightSimplePre(HIGHLIGHT_PRE);
        q.setHighlightSimplePost(HIGHLIGHT_POST);
        q.setHighlightFragsize(0); // don't fragment the highlight
        q.setParam("hl.maxAnalyzedChars", Server.HL_ANALYZE_CHARS_UNLIMITED); //analyze all content

        try {
            QueryResponse response = solrServer.query(q, METHOD.POST);
            Map<String, Map<String, List<String>>> responseHighlight = response.getHighlighting();

            Map<String, List<String>> responseHighlightID = responseHighlight.get(contentIdStr);
            if (responseHighlightID == null) {
                return NO_MATCHES;
            }
            List<String> contentHighlights = responseHighlightID.get(highLightField);
            if (contentHighlights == null) {
                return NO_MATCHES;
            } else {
                // extracted content (minus highlight tags) is HTML-escaped
                String highlightedContent = contentHighlights.get(0).trim();
                highlightedContent = insertAnchors(highlightedContent);


                return "<pre>" + highlightedContent + "</pre>";
            }
        } catch (NoOpenCoreException ex) {
            logger.log(Level.WARNING, "Couldn't query markup for page: " + currentPage, ex);
            return "";
        } catch (SolrServerException ex) {
            logger.log(Level.WARNING, "Could not query markup for page: " + currentPage, ex);
            return "";
        }
    }

    @Override
    public String toString() {
        return "Search Matches";
    }

    @Override
    public boolean isSearchable() {
        return true;
    }

    @Override
    public String getAnchorPrefix() {
        return ANCHOR_PREFIX;
    }

    @Override
    public int getNumberHits() {
        if (!this.hitsPages.containsKey(this.currentPage))
            return 0;
        return this.hitsPages.get(this.currentPage);
    }

    private String insertAnchors(String searchableContent) {
        int searchOffset = 0;
        int index = -1;

        StringBuilder buf = new StringBuilder(searchableContent);

        final String searchToken = HIGHLIGHT_PRE;
        final int indexSearchTokLen = searchToken.length();
        final String insertPre = "<a name='" + ANCHOR_PREFIX;
        final String insertPost = "'></a>";
        int count = 0;
        while ((index = buf.indexOf(searchToken, searchOffset)) >= 0) {
            String insertString = insertPre + Integer.toString(count + 1) + insertPost;
            int insertStringLen = insertString.length();
            buf.insert(index, insertString);
            searchOffset = index + indexSearchTokLen + insertStringLen; //next offset past this anchor
            ++count;
        }

        //store total hits for this page, now that we know it
        this.hitsPages.put(this.currentPage, count);
        if (this.currentItem() == 0 && this.hasNextItem()) {
            this.nextItem();
        }

        return buf.toString();
    }
    //dummy instance for Lookup only
    private static HighlightLookup instance = null;

    //getter of the singleton dummy instance solely for Lookup purpose
    //this instance does not actually work with Solr
    public static synchronized HighlightLookup getDefault() {
        if (instance == null) {
            instance = new HighlightedMatchesSource();
        }
        return instance;
    }

    @Override
    //factory method, i.e. invoked on dummy (Lookup) instance
    public HighlightLookup createInstance(Content c, String keywordHitQuery, boolean isRegex, String originalQuery) {
        return new HighlightedMatchesSource(c, keywordHitQuery, isRegex, originalQuery);
    }
}