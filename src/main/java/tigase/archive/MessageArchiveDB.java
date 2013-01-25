/*
 * Tigase Message Archiving Component
 * Copyright (C) 2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.archive;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import tigase.db.TigaseDBException;
import tigase.db.UserNotFoundException;
import tigase.db.UserRepository;
import tigase.server.Packet;
import tigase.xml.DomBuilderHandler;
import tigase.xml.Element;
import tigase.xml.SimpleParser;
import tigase.xml.SingletonFactory;
import tigase.xml.XMLUtils;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.XMPPResourceConnection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import tigase.db.DataRepository;
import tigase.db.RepositoryFactory;

public class MessageArchiveDB {

        private static final Logger log = Logger.getLogger(MessageArchiveDB.class.getCanonicalName());
        private static final long LONG_NULL = 0;
        private static final long MILIS_PER_DAY = 24 * 60 * 60 * 1000;
        private final static SimpleDateFormat formatter =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        private final static SimpleDateFormat formatter2 =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        private static final SimpleParser parser = SingletonFactory.getParserInstance();
        private DataRepository data_repo = null;
        // jids table
        private static final String JIDS_TABLE = "tig_ma_jids";
        private static final String JIDS_ID = "jid_id";
        private static final String JIDS_JID = "jid";
        private static final String ADD_JID_QUERY = "insert into " + JIDS_TABLE
                + " (" + JIDS_JID + ") values (?)";
        private static final String GET_JID_ID_QUERY = "select " + JIDS_JID + ", "
                + JIDS_ID + " from " + JIDS_TABLE + " where " + JIDS_JID + " = ?";
        private static final String GET_JID_IDS_QUERY = "select " + JIDS_JID + ", "
                + JIDS_ID + " from " + JIDS_TABLE + " where " + JIDS_JID + " = ?"
                + " or " + JIDS_JID + " = ?";
        private static final String PGSQL_CREATE_JIDS =
                "create table " + JIDS_TABLE + " ( " + JIDS_ID + " bigserial, "
                + JIDS_JID + " varchar(2049), primary key (" + JIDS_ID + ")); "
                + "create unique index " + JIDS_TABLE + "_" + JIDS_JID + " on "
                + JIDS_TABLE + " ( " + JIDS_JID + ");";
        private static final String MYSQL_CREATE_JIDS =
                "create table " + JIDS_TABLE + " ( " + JIDS_ID + " bigint unsigned NOT NULL auto_increment, "
                + JIDS_JID + " varchar(2049), primary key (" + JIDS_ID + ")); ";
//                + "create unique index " + JIDS_TABLE + "_" + JIDS_JID + " on "
//                + JIDS_TABLE + " ( " + JIDS_JID + "(765));";
        // messages table
        private static final String MSGS_TABLE = "tig_ma_msgs";
        private static final String MSGS_OWNER_ID = "owner_id";
        private static final String MSGS_BUDDY_ID = "buddy_id";
        private static final String MSGS_TIMESTAMP = "ts";
        private static final String MSGS_DIRECTION = "direction";
        private static final String MSGS_TYPE = "type";
        private static final String MSGS_MSG = "msg";
        private static final String ADD_MESSAGE = "insert into " + MSGS_TABLE
                + " (" + MSGS_OWNER_ID + ", " + MSGS_BUDDY_ID + ", " + MSGS_TIMESTAMP
                + ", " + MSGS_DIRECTION + ", " + MSGS_TYPE + ", " + MSGS_MSG + ")"
                + " values (?, ?, ?, ?, ?, ?)";
        private static final String GET_COLLECTIONS =
                "select distinct date(" + MSGS_TIMESTAMP + ")"
                + " from " + MSGS_TABLE + " where " + MSGS_OWNER_ID + " = ?"
                + " and " + MSGS_BUDDY_ID + " = ? and " + MSGS_TIMESTAMP + " <= ?"
                + " and " + MSGS_TIMESTAMP + " >= ?"
                + " order by date(" + MSGS_TIMESTAMP + ")";
        private static final String GET_MESSAGES =
                "select " + MSGS_MSG + " from " + MSGS_TABLE
                + " where " + MSGS_OWNER_ID + " = ? and " + MSGS_BUDDY_ID + " = ?"
                + " and date(" + MSGS_TIMESTAMP + ") = date(?)"
                + " order by " + MSGS_TIMESTAMP + " limit ? offset ?";
        private static final String REMOVE_MSGS = "delete from " + MSGS_TABLE
                + " where " + MSGS_OWNER_ID + " = ? and " + MSGS_BUDDY_ID + " = ?"
                + " and " + MSGS_TIMESTAMP + " <= ? and " + MSGS_TIMESTAMP + " >= ?";
//        private static final String GET_ALL_BUDDY_JIDS = "select jid from xtig_ma_jids "
//                + "where jid_id IN (select distinct buddy_id from xtig_ma_msgs "
//                + "     where owner_id = (select jid_id from xtig_ma_jids where jid = ?)"
//                + ")";
        private static final String PGSQL_CREATE_MSGS =
                "create table " + MSGS_TABLE + " (" + MSGS_OWNER_ID + " bigint, "
                + MSGS_BUDDY_ID + " bigint, " + MSGS_TIMESTAMP + " timestamp, "
                + MSGS_DIRECTION + " smallint, " + MSGS_TYPE + " varchar(10),"
                + MSGS_MSG + " text,"
                + " foreign key (" + MSGS_BUDDY_ID + ") references " + JIDS_TABLE
                + " (" + JIDS_ID + "),"
                + " foreign key (" + MSGS_OWNER_ID + ") references " + JIDS_TABLE
                + " (" + JIDS_ID + ") ); "
                + "create index " + MSGS_TABLE + "_" + MSGS_OWNER_ID + "_index on "
                + MSGS_TABLE + " ( " + MSGS_OWNER_ID + "); "
                + "create index " + MSGS_TABLE + "_" + MSGS_OWNER_ID
                + "_" + MSGS_BUDDY_ID + "_index on " + MSGS_TABLE + " ( "
                + MSGS_OWNER_ID + ", " + MSGS_BUDDY_ID + "); "
                + "create index " + MSGS_TABLE + "_" + MSGS_OWNER_ID
                + "_" + MSGS_TIMESTAMP + "_" + MSGS_BUDDY_ID + "_index on "
                + MSGS_TABLE + " ( " + MSGS_OWNER_ID + ", " + MSGS_BUDDY_ID + "); ";
        private static final String MYSQL_CREATE_MSGS =
                "create table " + MSGS_TABLE + " (" + MSGS_OWNER_ID + " bigint unsigned, "
                + MSGS_BUDDY_ID + " bigint unsigned, " + MSGS_TIMESTAMP + " timestamp, "
                + MSGS_DIRECTION + " smallint, " + MSGS_TYPE + " varchar(10),"
                + MSGS_MSG + " text,"
                + " foreign key (" + MSGS_BUDDY_ID + ") references " + JIDS_TABLE
                + " (" + JIDS_ID + "),"
                + " foreign key (" + MSGS_OWNER_ID + ") references " + JIDS_TABLE
                + " (" + JIDS_ID + ") ); ";
//                + "create index " + MSGS_TABLE + "_" + MSGS_OWNER_ID + "_index on "
//                + MSGS_TABLE + " ( " + MSGS_OWNER_ID + "); "
//                + "create index " + MSGS_TABLE + "_" + MSGS_OWNER_ID
//                + "_" + MSGS_BUDDY_ID + "_index on " + MSGS_TABLE + " ( "
//                + MSGS_OWNER_ID + ", " + MSGS_BUDDY_ID + "); "
//                + "create index " + MSGS_TABLE + "_" + MSGS_OWNER_ID
//                + "_" + MSGS_TIMESTAMP + "_" + MSGS_BUDDY_ID + "_index on "
//                + MSGS_TABLE + " ( " + MSGS_OWNER_ID + ", " + MSGS_BUDDY_ID + "); ";

        public void initRepository(String conn_str, Map<String, String> params) throws SQLException {
                try {
                        data_repo = RepositoryFactory.getDataRepository(null, conn_str, params);

                        // create tables if not exist
                        if (conn_str.startsWith("jdbc:mysql:")) {
                                data_repo.checkTable(JIDS_TABLE, MYSQL_CREATE_JIDS);
                                data_repo.checkTable(MSGS_TABLE, MYSQL_CREATE_MSGS);
                        } else {
                                data_repo.checkTable(JIDS_TABLE, PGSQL_CREATE_JIDS);
                                data_repo.checkTable(MSGS_TABLE, PGSQL_CREATE_MSGS);
                        }
                        data_repo.initPreparedStatement(ADD_JID_QUERY, ADD_JID_QUERY);
                        data_repo.initPreparedStatement(GET_JID_ID_QUERY, GET_JID_ID_QUERY);
                        data_repo.initPreparedStatement(GET_JID_IDS_QUERY, GET_JID_IDS_QUERY);
//			data_repo.initPreparedStatement(ADD_THREAD_QUERY, ADD_THREAD_QUERY);
//			data_repo.initPreparedStatement(GET_THREAD_ID_QUERY, GET_THREAD_ID_QUERY);
//			data_repo.initPreparedStatement(ADD_SUBJECT_QUERY, ADD_SUBJECT_QUERY);
//			data_repo.initPreparedStatement(GET_SUBJECT_ID_QUERY, GET_SUBJECT_ID_QUERY);

                        data_repo.initPreparedStatement(ADD_MESSAGE, ADD_MESSAGE);
                        data_repo.initPreparedStatement(GET_COLLECTIONS, GET_COLLECTIONS);
                        data_repo.initPreparedStatement(GET_MESSAGES, GET_MESSAGES);
                        data_repo.initPreparedStatement(REMOVE_MSGS, REMOVE_MSGS);
                } catch (Exception ex) {
                        log.log(Level.WARNING, "MessageArchiveDB initialization exception", ex);
                }
        }

        public void archiveMessage(BareJID owner, BareJID buddy, short direction, Element msg) {
                try {
                        String owner_str = owner.toString();
                        String buddy_str = buddy.toString();

                        long[] jids_ids = getJidsIds(owner_str, buddy_str);

                        long owner_id = jids_ids[0] != LONG_NULL ? jids_ids[0] : addJidId(owner_str);
                        long buddy_id = jids_ids[1] != LONG_NULL ? jids_ids[1] : addJidId(buddy_str);

                        java.sql.Timestamp mtime = null;
                        Element delay = msg.findChild("/message/delay");
                        if (delay != null) {
                                try {
                                        String stamp = delay.getAttribute("stamp");
                                        if (stamp.endsWith("Z")) {
                                                synchronized (formatter) {
                                                        mtime = new java.sql.Timestamp(formatter.parse(stamp).getTime());
                                                }
                                        } else {
                                                synchronized (formatter2) {
                                                        mtime = new java.sql.Timestamp(formatter2.parse(stamp).getTime());
                                                }
                                        }
                                } catch (ParseException e1) {
                                }
                        } else {
                                mtime = new java.sql.Timestamp(System.currentTimeMillis());
                        }

                        msg.addAttribute("time", String.valueOf(mtime.getTime()));

                        String type = msg.getAttribute("type");
                        String msgStr = msg.toString();
                        PreparedStatement add_message_st = data_repo.getPreparedStatement(owner, ADD_MESSAGE);
                        synchronized (add_message_st) {
                                add_message_st.setLong(1, owner_id);
                                add_message_st.setLong(2, buddy_id);
                                add_message_st.setTimestamp(3, mtime);
                                add_message_st.setShort(4, direction);
                                add_message_st.setString(5, type);
                                add_message_st.setString(6, msgStr);

                                add_message_st.executeUpdate();
                        }


                } catch (SQLException ex) {
                        log.log(Level.WARNING, "Problem adding new entry to DB: {0}", msg);
                } finally {
//                        data_repo.release(null, rs);
                }
        }

        public List<Element> getCollections(BareJID owner, String withJid, Date start, Date end, boolean before, int limit) throws SQLException {
                if (start == null) {
                        start = new Date(0);
                }
                if (end == null) {
                        end = new Date(0);
                }

//		boolean done = false;
                List<Element> results = new LinkedList<Element>();
                List<String> ids = getCollectionsPriv(owner, withJid, start, end, before, limit);

                for (String id : ids) {
                        results.add(new Element("chat", new String[]{"with", "start"}, new String[]{withJid, id}));
                }

                return results;
        }

        public List<Element> getItems(BareJID owner, String withJid, String start, int limit, int offset) throws SQLException {
                long[] jids_ids = getJidsIds(owner.toString(), withJid);
                ResultSet rs = null;
                StringBuilder buf = new StringBuilder(16 * 1024);
//                long collection = LONG_NULL;
                Timestamp collection = null;
                try {
                        synchronized (formatter) {
                                collection = new Timestamp(formatter.parse(start).getTime());// / MILIS_PER_DAY;
                        }

                        PreparedStatement get_messages_st = data_repo.getPreparedStatement(owner, GET_MESSAGES);
                        synchronized (get_messages_st) {
                                get_messages_st.setLong(1, jids_ids[0]);
                                get_messages_st.setLong(2, jids_ids[1]);
//                                get_messages_st.setLong(3, collection);
                                get_messages_st.setTimestamp(3, collection);
                                get_messages_st.setInt(4, limit);
                                get_messages_st.setInt(5, offset);

                                rs = get_messages_st.executeQuery();

                                while (rs.next()) {
                                        buf.append(rs.getString(1));
                                }
                        }
                } catch (ParseException ex) {
                        return null;
                } finally {
                        data_repo.release(null, rs);
                }

                List<Element> msgs = null;

                if (buf != null) {

                        String results = buf.toString();
                        msgs = new LinkedList<Element>();
                        DomBuilderHandler domHandler = new DomBuilderHandler();

                        parser.parse(domHandler, results.toCharArray(), 0, results.length());
                        Queue<Element> queue = domHandler.getParsedElements();

                        String ownerStr = owner.toString();
                        Element msg = null;
                        while ((msg = queue.poll()) != null) {
                                Element item = new Element(msg.getAttribute("from").startsWith(ownerStr) ? "to" : "from");
                                item.addChild(msg.getChild("body"));
                                item.setAttribute("secs", String.valueOf((Long.valueOf(msg.getAttribute("time")) - collection.getTime()) / 1000));
                                msgs.add(item);
                        }

                        Collections.sort(msgs, new Comparator<Element>() {

                                @Override
                                public int compare(Element m1, Element m2) {
                                        return m1.getAttribute("secs").compareTo(m2.getAttribute("secs"));
                                }
                        });
                }

                return msgs;
//                String node = ARCHIVE + "/messages/" + withJid + "/" + start.substring(0, 7);
//
//                List<Element> msgs = getMessages(session.getUserRepository(), session.getBareJID(), withJid, start.substring(0, 7), start.substring(8, 10));
//                if (msgs != null) {
//                        int after = 0;
//                        int before = 0;
//                        if (rsm.getAfter() != null) {
//                                after = Integer.valueOf(rsm.getAfter()) + 1;
//                        }
//                        if (rsm.getBefore() != null) {
//                                after = (Integer.valueOf(rsm.getBefore()) - 1) - rsm.getLimit();
//                        }
//                        if (after < 0) {
//                                after = 0;
//                        }
//                        before = after + rsm.getLimit();
//                        if (before > msgs.size()) {
//                                before = msgs.size();
//                        }
//                        int size = msgs.size();
//
//                        msgs = msgs.subList(after, before);
//                        List<Element> list = new LinkedList<Element>();
//                        long begin = 0;
//                        synchronized (nodeReaderFormatter) {
//                                try {
//                                        begin = nodeReaderFormatter.parse(start.substring(0, 10) + "T00:00:00").getTime();
//                                }
//                                catch (ParseException ex) {
//                                }
//                        }
//                        String user_id = session.getUserId().toString();
//                        while (!msgs.isEmpty()) {
//                                Element msg = msgs.remove(0);
//                                Element item = new Element(msg.getAttribute("from").startsWith(user_id) ? "to" : "from");
//                                item.addChild(msg.getChild("body"));
//                                item.setAttribute("secs", String.valueOf((Long.valueOf(msg.getAttribute("time")) - begin) / 1000));
//                                list.add(item);
//                        }
//                        rsm.setResults(size, String.valueOf(after), String.valueOf(before));
//                        return list;
//                }
//                return null;
        }

        public void removeItems(BareJID owner, String withJid, Date start, Date end) throws SQLException {
                long[] jids_ids = getJidsIds(owner.toString(), withJid);
                if (start == null) {
                        start = new Date(0);
                }
                if (end == null) {
                        end = new Date(0);
                }
                java.sql.Timestamp start_ = new java.sql.Timestamp(start.getTime());
                java.sql.Timestamp end_ = new java.sql.Timestamp(end.getTime());

                PreparedStatement remove_msgs_st = data_repo.getPreparedStatement(owner, REMOVE_MSGS);
                synchronized (remove_msgs_st) {
                        synchronized (remove_msgs_st) {
                                remove_msgs_st.setLong(1, jids_ids[0]);
                                remove_msgs_st.setLong(2, jids_ids[1]);
                                remove_msgs_st.setTimestamp(3, end_);
                                remove_msgs_st.setTimestamp(4, start_);

                                remove_msgs_st.executeUpdate();
                        }
                }
        }

        private List<String> getCollectionsPriv(BareJID owner, String withJid, Date start, Date end, boolean before, int limit) throws SQLException {
                long[] jids_ids = getJidsIds(owner.toString(), withJid);
                List<String> results = new LinkedList<String>();
                ResultSet rs = null;
                try {
                        java.sql.Timestamp start_ = new java.sql.Timestamp(start.getTime());
                        java.sql.Timestamp end_ = new java.sql.Timestamp(end.getTime());
                        PreparedStatement get_collections_st = data_repo.getPreparedStatement(owner, GET_COLLECTIONS);
                        synchronized (get_collections_st) {
                                get_collections_st.setLong(1, jids_ids[0]);
                                get_collections_st.setLong(2, jids_ids[1]);
                                get_collections_st.setTimestamp(3, end_);
                                get_collections_st.setTimestamp(4, start_);

                                rs = get_collections_st.executeQuery();

                                while (rs.next()) {
                                        Timestamp day = rs.getTimestamp(1);
                                        synchronized (formatter) {
                                                results.add(formatter.format(day));
                                        }
                                }
                        }

                        return results;
                } finally {
                        data_repo.release(null, rs);
                }

//                List<String> results = new LinkedList<String>();
//                String node = ARCHIVE + "/messages/" + withJid;
//                String[] monthss = repo.getKeys(user_id, node);
//                if (monthss != null) {
//                        List<String> months = new ArrayList<String>(Arrays.asList(monthss));
//                        Collections.sort(months);
//                        String sstart = start.substring(0, 7);
//                        String send = end.substring(0, 7);
//                        String sday = start.substring(8, 10);
//                        String eday = end.substring(8, 10);
//                        int i = 0;
//                        int j = limit;
//                        boolean done = false;
//                        while (j > 0 && i < months.size()) {
//                                String month = months.get(i);
//                                int b = sstart.compareTo(month);
//                                int e = send.compareTo(month);
//                                if (b > 0) {
//                                        i++;
//                                        if (before) {
//                                                j = 0;
//                                        }
//                                        continue;
//                                }
//                                if (e < 0) {
//                                        j = 0;
//                                        continue;
//                                }
//
//                                String[] dayss = repo.getKeys(user_id, node + "/" + month);
//                                List<String> days = new ArrayList<String>(Arrays.asList(dayss));
//                                Collections.sort(days);
//                                if (!before) {
//                                        int k = 0;
//                                        while (k < days.size() && j > 0) {
//                                                String day = days.get(k);
//                                                if ((day.compareTo(sday) >= 0) && (day.compareTo(eday) <= 0)) {
//                                                        results.add(month + "-" + day + "T00:00:00");
//                                                        j--;
//                                                }
//                                                k++;
//                                        }
//                                        i++;
//                                }
//                                else {
//                                        int k = days.size();
//                                        while (k > 0 && j > 0) {
//                                                String day = days.get(k - 1);
//                                                if ((day.compareTo(sday) >= 0) && (day.compareTo(eday) <= 0)) {
//                                                        results.add(month + "-" + day + "T00:00:00");
//                                                        j--;
//                                                }
//                                                k--;
//                                        }
//                                        i--;
//                                }
//                        }
//                }
//                if (before) {
//                        Collections.reverse(results);
//                }
//
//                return results;
        }

//        public static List<Element> getMessages(UserRepository repo, BareJID user_id, String jid, String month, String day) throws TigaseDBException {
//                String results = repo.getData(user_id, ARCHIVE + "/messages/" + jid + "/" + month, day);
//                List<Element> msgs = null;
//
//                if (results != null) {
//                        msgs = new LinkedList<Element>();
//                        DomBuilderHandler domHandler = new DomBuilderHandler();
//
//                        parser.parse(domHandler, results.toCharArray(), 0, results.length());
//                        Queue<Element> queue = domHandler.getParsedElements();
//
//                        msgs.addAll(queue);
//
//                        Collections.sort(msgs, new Comparator<Element>() {
//
//                                @Override
//                                public int compare(Element m1, Element m2) {
//                                        return m1.getAttribute("time").compareTo(m2.getAttribute("time"));
//                                }
//                        });
//                }
//                return msgs;
//        }
        protected long[] getJidsIds(String... jids) throws SQLException {
                ResultSet rs = null;

                try {
                        long[] results = new long[jids.length];
                        Arrays.fill(results, LONG_NULL);

                        if (jids.length == 1) {
                                PreparedStatement get_jid_id_st = data_repo.getPreparedStatement(null, GET_JID_ID_QUERY);

                                synchronized (get_jid_id_st) {
                                        get_jid_id_st.setString(1, jids[0]);
                                        rs = get_jid_id_st.executeQuery();

                                        if (rs.next()) {
                                                results[0] = rs.getLong("jid_id");

                                                return results;
                                        }
                                }

                                return null;
                        } else {
                                PreparedStatement get_jids_id_st = data_repo.getPreparedStatement(null, GET_JID_IDS_QUERY);

                                synchronized (get_jids_id_st) {
                                        for (int i = 0; i < jids.length; i++) {
                                                get_jids_id_st.setString(i + 1, jids[i]);
                                        }

                                        rs = get_jids_id_st.executeQuery();

                                        int cnt = 0;

                                        while (rs.next()) {
                                                String db_jid = rs.getString("jid");

                                                for (int i = 0; i < jids.length; i++) {
                                                        if (db_jid.equals(jids[i])) {
                                                                results[i] = rs.getLong("jid_id");
                                                                ++cnt;
                                                        }
                                                }
                                        }

                                        return results;
                                }
                        }
                } finally {
                        data_repo.release(null, rs);
                }
        }

        private long addJidId(String jid) throws SQLException {
                PreparedStatement add_jid_st = data_repo.getPreparedStatement(null, ADD_JID_QUERY);

                synchronized (add_jid_st) {
                        add_jid_st.setString(1, jid);
                        add_jid_st.executeUpdate();
                }

                // This is not the most effective solution but this method shouldn't be
                // called very often so the perfrmance impact should be insignificant.
                long[] jid_ids = getJidsIds(jid);

                if (jid_ids != null) {
                        return jid_ids[0];
                } else {

                        // That should never happen here, but just in case....
                        log.log(Level.WARNING, "I have just added new jid but it was not found.... {0}", jid);

                        return LONG_NULL;
                }
        }
}