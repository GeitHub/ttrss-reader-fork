/*
 * ttrss-reader-fork for Android
 * 
 * Copyright (C) 2010 N. Braden.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package org.ttrssreader.controllers;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.ttrssreader.R;
import org.ttrssreader.model.pojos.Article;
import org.ttrssreader.model.pojos.Category;
import org.ttrssreader.model.pojos.Feed;
import org.ttrssreader.model.pojos.Label;
import org.ttrssreader.net.IArticleOmitter;
import org.ttrssreader.net.IdUpdatedArticleOmitter;
import org.ttrssreader.net.JSONConnector;
import org.ttrssreader.net.StopJsonParsingException;
import org.ttrssreader.utils.Utils;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Log;

@SuppressLint("UseSparseArrays")
public class Data {
    
    /** uncategorized */
    public static final int VCAT_UNCAT = 0;
    
    /** starred */
    public static final int VCAT_STAR = -1;
    
    /** published */
    public static final int VCAT_PUB = -2;
    
    /** fresh */
    public static final int VCAT_FRESH = -3;
    
    /** all articles */
    public static final int VCAT_ALL = -4;
    
    /** read articles */
    public static final int VCAT_READ = -6;
    
    public static final int TIME_CATEGORY = 1;
    public static final int TIME_FEED = 2;
    public static final int TIME_FEEDHEADLINE = 3;
    
    private static final String VIEW_ALL = "all_articles";
    private static final String VIEW_UNREAD = "unread";
    
    private Context context;
    
    private long time = 0;
    
    private long articlesCached;
    private Map<Integer, Long> articlesChanged = new HashMap<Integer, Long>();
    
    /** map of category id to last changed time */
    private Map<Integer, Long> feedsChanged = new HashMap<Integer, Long>();
    private long virtCategoriesChanged = 0;
    private long categoriesChanged = 0;
    
    private ConnectivityManager cm;
    
    // Singleton (see http://stackoverflow.com/a/11165926)
    private Data() {
    }
    
    private static class InstanceHolder {
        private static final Data instance = new Data();
    }
    
    public static Data getInstance() {
        return InstanceHolder.instance;
    }
    
    public synchronized void checkAndInitializeData(final Context context) {
        this.context = context;
        if (context != null)
            cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
    
    // *** ARTICLES *********************************************************************
    
    /**
     * cache all articles
     * 
     * @param overrideOffline
     *            do not check connected state
     * @param overrideDelay
     *            if set to {@code true} enforces the update,
     *            otherwise the time from last update will be
     *            considered
     */
    public void cacheArticles(boolean overrideOffline, boolean overrideDelay) {
        
        int limit = 400;
        if (Controller.getInstance().isLowMemory())
            limit = limit / 2;
        
        if (!overrideDelay && (time > (System.currentTimeMillis() - Utils.UPDATE_TIME))) {
            Log.d(Utils.TAG, "Skip articles caching");
            return;
        } else if (!Utils.isConnected(cm) && !(overrideOffline && Utils.checkConnected(cm))) {
            return;
        }
        
        Set<Article> articles = new HashSet<Article>();
        int sinceId = Controller.getInstance().getSinceId();
        
        IdUpdatedArticleOmitter unreadUpdatedFilter = new IdUpdatedArticleOmitter("isUnread>0", null);
        Controller
                .getInstance()
                .getConnector()
                .getHeadlines(articles, VCAT_ALL, limit, VIEW_UNREAD, true, 0, null, null,
                        unreadUpdatedFilter.getIdUpdatedMap().isEmpty() ? null : unreadUpdatedFilter);
        
        final Article newestCachedArticle = DBHelper.getInstance().getArticle(sinceId);
        IArticleOmitter updatedFilter = (newestCachedArticle == null) ? null : new IArticleOmitter() {
            public Date lastUpdated = newestCachedArticle.updated;
            
            @Override
            public boolean omitArticle(Article.ArticleField field, Article a) throws StopJsonParsingException {
                boolean skip = false;
                
                switch (field) {
                    case unread:
                        if (a.isUnread)
                            skip = true;
                        break;
                    case updated:
                        if (a.updated != null && lastUpdated.after(a.updated))
                            throw new StopJsonParsingException("Stop processing on article ID=" + a.id + " updated on "
                                    + lastUpdated);
                    default:
                        break;
                }
                
                return skip;
            }
        };
        Controller.getInstance().getConnector()
                .getHeadlines(articles, VCAT_ALL, limit, VIEW_ALL, true, sinceId, null, null, updatedFilter);
        
        Log.d(Utils.TAG, "Got " + articles.size() + " articles to be cached");
        handleInsertArticles(articles, VCAT_ALL, true, true);
        
        // Only mark as updated if the calls were successful
        if (!articles.isEmpty() || !unreadUpdatedFilter.getOmittedArticles().isEmpty()) {
            time = System.currentTimeMillis();
            notifyListeners();
            
            // Store all category-ids and ids of all feeds for this category in db
            articlesCached = time;
            for (Category c : DBHelper.getInstance().getAllCategories()) {
                feedsChanged.put(c.id, time);
            }
            
            Set<Integer> articleUnreadIds = new HashSet<Integer>();
            for (Article a : articles) {
                if (a.isUnread) {
                    articleUnreadIds.add(Integer.valueOf(a.id));
                }
            }
            
            articleUnreadIds.addAll(unreadUpdatedFilter.getOmittedArticles());
            Log.d(Utils.TAG, "Amount of unread articles: " + articleUnreadIds.size());
            DBHelper.getInstance().markRead(VCAT_ALL, false);
            DBHelper.getInstance().markArticles(articleUnreadIds, "isUnread", 1);
        }
    }
    
    /**
     * update articles for specified feed/category
     * 
     * @param feedId
     *            feed/category to be updated
     * @param displayOnlyUnread
     *            flag, that indicates, that only unread
     *            articles should be shown
     * @param isCat
     *            if set to {@code true}, then {@code feedId} is actually the category ID
     * @param overrideOffline
     *            should the "work offline" state be ignored?
     * @param overrideDelay
     *            should the last update time be ignored?
     */
    public void updateArticles(int feedId, boolean displayOnlyUnread, boolean isCat, boolean overrideOffline, boolean overrideDelay) {
        Long time = articlesChanged.get(feedId);
        if (isCat) // Category-Ids are in feedsChanged
            time = feedsChanged.get(feedId);
        
        if (time == null)
            time = Long.valueOf(0);
        
        if (articlesCached > time && !(feedId == VCAT_PUB || feedId == VCAT_STAR))
            time = articlesCached;
        
        if (!overrideDelay && time > System.currentTimeMillis() - Utils.UPDATE_TIME) {
            return;
        } else if (!Utils.isConnected(cm) && !(overrideOffline && Utils.checkConnected(cm))) {
            return;
        }
        
        int sinceId = Controller.getInstance().getSinceId();
        
        if (feedId == VCAT_PUB || feedId == VCAT_STAR) {
            displayOnlyUnread = false; // Display all articles for Starred/Published
        }
        sinceId = 0; // Also reset sinceId since we explicitly want older articles too
        
        // Calculate an appropriate upper limit for the number of articles
        int limit = calculateLimit(feedId, displayOnlyUnread, isCat);
        if (Controller.getInstance().isLowMemory())
            limit = limit / 2;
        
        Log.d(Utils.TAG, "UPDATE limit: " + limit);
        String viewMode = (displayOnlyUnread ? VIEW_UNREAD : VIEW_ALL);
        
        Set<Article> articles = new HashSet<Article>();
        if (!displayOnlyUnread) {
            // If not displaying only unread articles: Refresh unread articles to get them too.
            Controller.getInstance().getConnector().getHeadlines(articles, feedId, limit, VIEW_UNREAD, isCat);
        }
        Controller.getInstance().getConnector().getHeadlines(articles, feedId, limit, viewMode, isCat, sinceId);
        handleInsertArticles(articles, feedId, isCat, false);
        
        long currentTime = System.currentTimeMillis();
        // Store requested feed-/category-id and ids of all feeds in db for this category if a category was requested
        articlesChanged.put(feedId, currentTime);
        notifyListeners();
        
        if (isCat) {
            for (Feed f : DBHelper.getInstance().getFeeds(feedId)) {
                articlesChanged.put(f.id, currentTime);
            }
        }
    }
    
    public void searchArticles(int feedId, boolean isCat, boolean overrideOffline) {
        if (Utils.isConnected(cm) || (overrideOffline && Utils.checkConnected(cm))) {
            
            Set<Article> articles = new HashSet<Article>();
            Controller.getInstance().getConnector().getHeadlines(articles, feedId, 400, VIEW_ALL, isCat);
            
            // TODO: articles in DB packen oder direkt irgendwie zurueck geben? Wenn ja, wohin?
            // Temporaere-DB erstellen und Adapter so umbauen, dass er Suchergebnisse automatisch daraus laedt?
            
        }
    }
    
    /**
     * Calculate an appropriate upper limit for the number of articles
     */
    private int calculateLimit(int feedId, boolean displayOnlyUnread, boolean isCat) {
        int limit = -1;
        switch (feedId) {
            case VCAT_STAR: // Starred
            case VCAT_PUB: // Published
                limit = JSONConnector.PARAM_LIMIT_MAX_VALUE;
                break;
            case VCAT_FRESH: // Fresh
                limit = DBHelper.getInstance().getUnreadCount(feedId, true);
                break;
            case VCAT_ALL: // All Articles
                limit = DBHelper.getInstance().getUnreadCount(feedId, true);
                break;
            default: // Normal categories
                limit = DBHelper.getInstance().getUnreadCount(feedId, isCat);
        }
        if (feedId < -10 && limit <= 0) // Unread-count in DB is wrong for Labels since we only count articles with
                                        // feedid = ?
            limit = 50;
        return limit;
    }
    
    /**
     * prepare the DB and store given articles
     * 
     * @param articles
     *            articles to be stored
     * @param isCategory
     */
    private void handleInsertArticles(final Collection<Article> articles, int feedId, boolean isCategory, boolean isCaching) {
        if (!articles.isEmpty()) {
            
            // Search min and max ids
            int minId = Integer.MAX_VALUE;
            int maxId = Integer.MIN_VALUE;
            for (Article article : articles) {
                if (article.id > maxId)
                    maxId = article.id;
                if (article.id < minId)
                    minId = article.id;
            }
            
            DBHelper.getInstance().purgeLastArticles(articles.size());
            
            // We set isCategory=false for starred/published articles...
            // if ((feedId == Data.VCAT_STAR || feedId == Data.VCAT_PUB) && !isCategory)
            // TODO FIXME is it necessary? Articles will be completely DELETED it is probably wrong!!!
            // DBHelper.getInstance().purgeVirtualCategories(minId);
            
            DBHelper.getInstance().insertArticles(articles);
            
            // correct counters according to real local DB-Data
            DBHelper.getInstance().calculateCounters();
            notifyListeners();
            
            // Only store sinceId when doing a full cache of new articles, else it doesn't work.
            if (isCaching) {
                Controller.getInstance().setSinceId(maxId);
                Controller.getInstance().setLastSync(System.currentTimeMillis());
            }
        }
    }
    
    // *** FEEDS ************************************************************************
    
    /**
     * update DB (delete/insert) with actual feeds information from server
     * 
     * @param categoryId
     *            id of category, which feeds should be returned
     * @param overrideOffline
     *            do not check connected state
     * 
     * @return actual feeds for given category
     */
    public Set<Feed> updateFeeds(int categoryId, boolean overrideOffline) {
        
        Long time = feedsChanged.get(categoryId);
        if (time == null)
            time = Long.valueOf(0);
        
        if (time > System.currentTimeMillis() - Utils.UPDATE_TIME) {
            return null;
        } else if (Utils.isConnected(cm) || (overrideOffline && Utils.checkConnected(cm))) {
            Set<Feed> ret = new LinkedHashSet<Feed>();
            Set<Feed> feeds = Controller.getInstance().getConnector().getFeeds();
            
            // Only delete feeds if we got new feeds...
            if (!feeds.isEmpty()) {
                for (Feed f : feeds) {
                    if (categoryId == VCAT_ALL || f.categoryId == categoryId)
                        ret.add(f);
                    
                    feedsChanged.put(f.categoryId, System.currentTimeMillis());
                }
                DBHelper.getInstance().deleteFeeds();
                DBHelper.getInstance().insertFeeds(feeds);
                
                // Store requested category-id and ids of all received feeds
                feedsChanged.put(categoryId, System.currentTimeMillis());
                notifyListeners();
            }
            
            return ret;
        }
        return null;
    }
    
    // *** CATEGORIES *******************************************************************
    
    public Set<Category> updateVirtualCategories() {
        if (virtCategoriesChanged > System.currentTimeMillis() - Utils.UPDATE_TIME)
            return null;
        
        String vCatAllArticles = "";
        String vCatFreshArticles = "";
        String vCatPublishedArticles = "";
        String vCatStarredArticles = "";
        String uncatFeeds = "";
        
        if (context != null) {
            vCatAllArticles = (String) context.getText(R.string.VCategory_AllArticles);
            vCatFreshArticles = (String) context.getText(R.string.VCategory_FreshArticles);
            vCatPublishedArticles = (String) context.getText(R.string.VCategory_PublishedArticles);
            vCatStarredArticles = (String) context.getText(R.string.VCategory_StarredArticles);
            uncatFeeds = (String) context.getText(R.string.Feed_UncategorizedFeeds);
        }
        
        Set<Category> vCats = new LinkedHashSet<Category>();
        vCats.add(new Category(VCAT_ALL, vCatAllArticles, DBHelper.getInstance().getUnreadCount(VCAT_ALL, true)));
        vCats.add(new Category(VCAT_FRESH, vCatFreshArticles, DBHelper.getInstance().getUnreadCount(VCAT_FRESH, true)));
        vCats.add(new Category(VCAT_PUB, vCatPublishedArticles, DBHelper.getInstance().getUnreadCount(VCAT_PUB, true)));
        vCats.add(new Category(VCAT_STAR, vCatStarredArticles, DBHelper.getInstance().getUnreadCount(VCAT_STAR, true)));
        vCats.add(new Category(VCAT_UNCAT, uncatFeeds, DBHelper.getInstance().getUnreadCount(VCAT_UNCAT, true)));
        
        DBHelper.getInstance().insertCategories(vCats);
        notifyListeners();
        
        virtCategoriesChanged = System.currentTimeMillis();
        
        return vCats;
    }
    
    /**
     * update DB (delete/insert) with actual categories information from server
     * 
     * @param overrideOffline
     *            do not check connected state
     * 
     * @return actual categories
     */
    public Set<Category> updateCategories(boolean overrideOffline) {
        if (categoriesChanged > System.currentTimeMillis() - Utils.UPDATE_TIME) {
            return null;
        } else if (Utils.isConnected(cm) || overrideOffline) {
            Set<Category> categories = Controller.getInstance().getConnector().getCategories();
            
            if (!categories.isEmpty()) {
                DBHelper.getInstance().deleteCategories(false);
                DBHelper.getInstance().insertCategories(categories);
                
                categoriesChanged = System.currentTimeMillis();
                notifyListeners();
            }
            
            return categories;
        }
        return null;
    }
    
    // *** STATUS *******************************************************************
    
    public void setArticleRead(Set<Integer> ids, int articleState) {
        boolean erg = false;
        if (Utils.isConnected(cm))
            erg = Controller.getInstance().getConnector().setArticleRead(ids, articleState);
        
        if (!erg)
            DBHelper.getInstance().markUnsynchronizedStates(ids, DBHelper.MARK_READ, articleState);
    }
    
    public void setArticleStarred(int articleId, int articleState) {
        boolean erg = false;
        Set<Integer> ids = new HashSet<Integer>();
        ids.add(articleId);
        
        if (Utils.isConnected(cm))
            erg = Controller.getInstance().getConnector().setArticleStarred(ids, articleState);
        
        if (!erg)
            DBHelper.getInstance().markUnsynchronizedStates(ids, DBHelper.MARK_STAR, articleState);
    }
    
    public void setArticlePublished(int articleId, int articleState, String note) {
        boolean erg = false;
        Map<Integer, String> ids = new HashMap<Integer, String>();
        ids.put(articleId, note);
        
        if (Utils.isConnected(cm))
            erg = Controller.getInstance().getConnector().setArticlePublished(ids, articleState);
        
        // Write changes to cache if calling the server failed
        if (!erg) {
            DBHelper.getInstance().markUnsynchronizedStates(ids.keySet(), DBHelper.MARK_PUBLISH, articleState);
            DBHelper.getInstance().markUnsynchronizedNotes(ids, DBHelper.MARK_PUBLISH);
        }
    }
    
    /**
     * mark all articles in given category/feed as read
     * 
     * @param id
     *            category/feed ID
     * @param isCategory
     *            if set to {@code true}, then given id is category
     *            ID, otherwise - feed ID
     */
    public void setRead(int id, boolean isCategory) {
        
        Collection<Integer> markedArticleIds = DBHelper.getInstance().markRead(id, isCategory);
        
        if (markedArticleIds != null) {
            notifyListeners();
            
            boolean isSync = false;
            if (Utils.isConnected(cm)) {
                isSync = Controller.getInstance().getConnector().setRead(id, isCategory);
            }
            
            if (!isSync) {
                DBHelper.getInstance().markUnsynchronizedStates(markedArticleIds, DBHelper.MARK_READ, 0);
            }
        }
        
    }
    
    public boolean shareToPublished(String title, String url, String content) {
        if (Utils.isConnected(cm))
            return Controller.getInstance().getConnector().shareToPublished(title, url, content);
        return false;
    }
    
    public JSONConnector.SubscriptionResponse feedSubscribe(String feed_url, int category_id) {
        if (Utils.isConnected(cm))
            return Controller.getInstance().getConnector().feedSubscribe(feed_url, category_id);
        return null;
    }
    
    public boolean feedUnsubscribe(int feed_id) {
        if (Utils.isConnected(cm))
            return Controller.getInstance().getConnector().feedUnsubscribe(feed_id);
        return false;
    }
    
    public String getPref(String pref) {
        if (Utils.isConnected(cm))
            return Controller.getInstance().getConnector().getPref(pref);
        return null;
    }
    
    public int getVersion() {
        if (Utils.isConnected(cm))
            return Controller.getInstance().getConnector().getVersion();
        return -1;
    }
    
    public Set<Label> getLabels(int articleId) {
        Set<Label> ret = DBHelper.getInstance().getLabelsForArticle(articleId);
        return ret;
    }
    
    public boolean setLabel(Integer articleId, Label label) {
        Set<Integer> set = new HashSet<Integer>();
        set.add(articleId);
        return setLabel(set, label);
    }
    
    public boolean setLabel(Set<Integer> articleIds, Label label) {
        
        DBHelper.getInstance().insertLabels(articleIds, label, label.checked);
        notifyListeners();
        
        boolean erg = false;
        if (Utils.isConnected(cm)) {
            Log.d(Utils.TAG, "Calling connector with Label: " + label + ") and ids.size() " + articleIds.size());
            erg = Controller.getInstance().getConnector().setArticleLabel(articleIds, label.id, label.checked);
        }
        return erg;
    }
    
    /**
     * syncronize read, starred, published articles and notes with server
     */
    public void synchronizeStatus() {
        if (!Utils.isConnected(cm))
            return;
        
        Log.d(Utils.TAG, "Start status sync");
        
        String[] marks = new String[] { DBHelper.MARK_READ, DBHelper.MARK_STAR, DBHelper.MARK_PUBLISH,
                DBHelper.MARK_NOTE };
        for (String mark : marks) {
            Map<Integer, String> idsMark = DBHelper.getInstance().getMarked(mark, 1);
            Map<Integer, String> idsUnmark = DBHelper.getInstance().getMarked(mark, 0);
            
            // Log.d(Utils.TAG, "Syncing status '" + mark + "' mark count: " + idsMark.size() + " unmark count:"
            // + idsUnmark.size());
            
            if (DBHelper.MARK_READ.equals(mark)) {
                if (Controller.getInstance().getConnector().setArticleRead(idsMark.keySet(), 1))
                    DBHelper.getInstance().setMarked(idsMark, mark);
                
                if (Controller.getInstance().getConnector().setArticleRead(idsUnmark.keySet(), 0))
                    DBHelper.getInstance().setMarked(idsUnmark, mark);
            }
            if (DBHelper.MARK_STAR.equals(mark)) {
                if (Controller.getInstance().getConnector().setArticleStarred(idsMark.keySet(), 1))
                    DBHelper.getInstance().setMarked(idsMark, mark);
                
                if (Controller.getInstance().getConnector().setArticleStarred(idsUnmark.keySet(), 0))
                    DBHelper.getInstance().setMarked(idsUnmark, mark);
            }
            if (DBHelper.MARK_PUBLISH.equals(mark)) {
                if (Controller.getInstance().getConnector().setArticlePublished(idsMark, 1))
                    DBHelper.getInstance().setMarked(idsMark, mark);
                
                if (Controller.getInstance().getConnector().setArticlePublished(idsUnmark, 0))
                    DBHelper.getInstance().setMarked(idsUnmark, mark);
            }
            // TODO: Add synchronization of labels
        }
        
        /*
         * Server doesn't seem to support ID -6 for "read articles" so I'll stick with the already fetched articles. In
         * the case that we had a cache-run before we can just mark everything as read, then mark all cached articles as
         * unread and we're done.
         */
        
        // get read articles from server
        // articles = new HashSet<Article>();
        // int minUnread = DBHelper.getInstance().getMinUnreadId();
        // Set<String> skipProperties = new HashSet<String>(Arrays.asList(new String[] { JSONConnector.TITLE,
        // JSONConnector.UNREAD, JSONConnector.UPDATED, JSONConnector.FEED_ID, JSONConnector.CONTENT,
        // JSONConnector.URL, JSONConnector.COMMENT_URL, JSONConnector.ATTACHMENTS, JSONConnector.STARRED,
        // JSONConnector.PUBLISHED }));
        //
        // Controller.getInstance().getConnector()
        // .getHeadlines(articles, VCAT_ALL, 400, VIEW_ALL, true, minUnread, null, skipProperties);
        
        Log.d(Utils.TAG, "Status is synced");
    }
    
    public void purgeOrphanedArticles() {
        if (Controller.getInstance().getLastCleanup() > System.currentTimeMillis() - Utils.CLEANUP_TIME)
            return;
        
        DBHelper.getInstance().purgeOrphanedArticles();
        Controller.getInstance().setLastCleanup(System.currentTimeMillis());
    }
    
    private void notifyListeners() {
        if (!Controller.getInstance().isHeadless())
            UpdateController.getInstance().notifyListeners();
    }
    
}
