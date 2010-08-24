/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.mail.store;

import com.android.email.Email;
import com.android.email.FixedLengthInputStream;
import com.android.email.PeekableInputStream;
import com.android.email.mail.MessagingException;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.email.mail.transport.LoggingInputStream;

import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ImapResponseParser {
    // DEBUG ONLY - Always check in as "false"
    private static boolean DEBUG_LOG_RAW_STREAM = false;
    
    // mDateTimeFormat is used only for parsing IMAP's FETCH ENVELOPE command, in which
    // en_US-like date format is used like "01-Jan-2009 11:20:39 -0800", so this should be
    // handled by Locale.US
    private final static SimpleDateFormat DATE_TIME_FORMAT =
            new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US);
    private final PeekableInputStream mIn;
    private InputStream mActiveLiteral;

    /**
     * To log network activities when the parser crashes.
     *
     * <p>We log all bytes received from the server, except for the part sent as literals.
     */
    private final DiscourseLogger mDiscourseLogger;

    public ImapResponseParser(InputStream in, DiscourseLogger discourseLogger) {
        if (DEBUG_LOG_RAW_STREAM && Config.LOGD && Email.DEBUG) {
            in = new LoggingInputStream(in);
        }
        this.mIn = new PeekableInputStream(in);
        mDiscourseLogger = discourseLogger;
    }

    /**
     * Read and return one byte from {@link #mIn}, and put it in {@link #mDiscourseLogger}.
     * Return -1 when EOF.
     */
    private int readByte() throws IOException {
        int ret = mIn.read();
        if (ret != -1) {
            mDiscourseLogger.addReceivedByte(ret);
        }
        return ret;
    }

    /**
     * Reads the next response available on the stream and returns an
     * ImapResponse object that represents it.
     * @return the parsed {@link ImapResponse} object.
     */
    public ImapResponse readResponse() throws IOException {
        try {
            ImapResponse response = new ImapResponse();
            if (mActiveLiteral != null) {
                while (mActiveLiteral.read() != -1)
                    ;
                mActiveLiteral = null;
            }
            int ch = mIn.peek();
            if (ch == '*') {
                parseUntaggedResponse();
                readTokens(response);
            } else if (ch == '+') {
                response.mCommandContinuationRequested =
                        parseCommandContinuationRequest();
                readTokens(response);
            } else {
                response.mTag = parseTaggedResponse();
                readTokens(response);
            }
            if (Config.LOGD) {
                if (Email.DEBUG) {
                    Log.d(Email.LOG_TAG, "<<< " + response.toString());
                }
            }
            return response;
        } catch (RuntimeException e) {
            // Parser crash -- log network activities.
            onParseError(e);
            throw e;
        } catch (IOException e) {
            // Network error, or received an unexpected char.
            onParseError(e);
            throw e;
        }
    }

    private void onParseError(Exception e) {
        // Read a few more bytes, so that the log will contain some more context, even if the parser
        // crashes in the middle of a response.
        // This also makes sure the byte in question will be logged, no matter where it crashes.
        // e.g. when parseAtom() peeks and finds at an unexpected char, it throws an exception
        // before actually reading it.
        // However, we don't want to read too much, because then it may get into an email message.
        try {
            for (int i = 0; i < 4; i++) {
                int b = readByte();
                if (b == -1 || b == '\n') {
                    break;
                }
            }
        } catch (IOException ignore) {
        }
        Log.w(Email.LOG_TAG, "Exception detected: " + e.getMessage());
        mDiscourseLogger.logLastDiscourse();
    }

    private void readTokens(ImapResponse response) throws IOException {
        response.clear();
        Object token;
        while ((token = readToken()) != null) {
            if (response != null) {
                response.add(token);
            }
            if (mActiveLiteral != null) {
                break;
            }
        }
        response.mCompleted = token == null;
    }

    /**
     * Reads the next token of the response. The token can be one of: String -
     * for NIL, QUOTED, NUMBER, ATOM. InputStream - for LITERAL.
     * InputStream.available() returns the total length of the stream.
     * ImapResponseList - for PARENTHESIZED LIST. Can contain any of the above
     * elements including List.
     *
     * @return The next token in the response or null if there are no more
     *         tokens.
     * @throws IOException
     */
    public Object readToken() throws IOException {
        while (true) {
            Object token = parseToken();
            if (token == null || !(token.equals(")") || token.equals("]"))) {
                return token;
            }
        }
    }

    private Object parseToken() throws IOException {
        if (mActiveLiteral != null) {
            while (mActiveLiteral.read() != -1)
                ;
            mActiveLiteral = null;
        }
        while (true) {
            int ch = mIn.peek();
            if (ch == '(') {
                return parseList('(', ")");
            } else if (ch == ')') {
                expect(')');
                return ")";
            } else if (ch == '[') {
                return parseList('[', "]");
            } else if (ch == ']') {
                expect(']');
                return "]";
            } else if (ch == '"') {
                return parseQuoted();
            } else if (ch == '{') {
                mActiveLiteral = parseLiteral();
                return mActiveLiteral;
            } else if (ch == ' ') {
                expect(' ');
            } else if (ch == '\r') {
                expect('\r');
                expect('\n');
                return null;
            } else if (ch == '\n') {
                expect('\n');
                return null;
            } else {
                return parseAtom();
            }
        }
    }

    private boolean parseCommandContinuationRequest() throws IOException {
        expect('+');
        expect(' ');
        return true;
    }

    // * OK [UIDNEXT 175] Predicted next UID
    private void parseUntaggedResponse() throws IOException {
        expect('*');
        expect(' ');
    }

    // 3 OK [READ-WRITE] Select completed.
    private String parseTaggedResponse() throws IOException {
        String tag = readStringUntil(' ');
        return tag;
    }

    /**
     * @param opener The char that the list opens with
     * @param closer The char that ends the list
     * @return a list object containing the elements of the list
     * @throws IOException
     */
    private ImapList parseList(char opener, String closer) throws IOException {
        expect(opener);
        ImapList list = new ImapList();
        Object token;
        while (true) {
            token = parseToken();
            if (token == null) {
                break;
            } else if (token instanceof InputStream) {
                list.add(token);
                break;
            } else if (token.equals(closer)) {
                break;
            } else {
                list.add(token);
            }
        }
        return list;
    }

    private String parseAtom() throws IOException {
        StringBuffer sb = new StringBuffer();
        int ch;
        while (true) {
            ch = mIn.peek();
            if (ch == -1) {
                if (Config.LOGD && Email.DEBUG) {
                    Log.d(Email.LOG_TAG, "parseAtom(): end of stream reached");
                }
                throw new IOException("parseAtom(): end of stream reached");
            } else if (ch == '(' || ch == ')' || ch == '{' || ch == ' ' ||
                    // ']' is not part of atom (it's in resp-specials)
                    ch == ']' ||
                    // docs claim that flags are \ atom but atom isn't supposed to
                    // contain
                    // * and some flags contain *
                    // ch == '%' || ch == '*' ||
                    ch == '%' ||
                    // TODO probably should not allow \ and should recognize
                    // it as a flag instead
                    // ch == '"' || ch == '\' ||
                    ch == '"' || (ch >= 0x00 && ch <= 0x1f) || ch == 0x7f) {
                if (sb.length() == 0) {
                    throw new IOException(String.format("parseAtom(): (%04x %c)", ch, ch));
                }
                return sb.toString();
            } else {
                sb.append((char)readByte());
            }
        }
    }

    /**
     * A { has been read, read the rest of the size string, the space and then
     * notify the listener with an InputStream.
     *
     * @param mListener
     * @throws IOException
     */
    private InputStream parseLiteral() throws IOException {
        expect('{');
        int size = Integer.parseInt(readStringUntil('}'));
        expect('\r');
        expect('\n');
        FixedLengthInputStream fixed = new FixedLengthInputStream(mIn, size);
        return fixed;
    }

    /**
     * A " has been read, read to the end of the quoted string and notify the
     * listener.
     *
     * @param mListener
     * @throws IOException
     */
    private String parseQuoted() throws IOException {
        expect('"');
        return readStringUntil('"');
    }

    private String readStringUntil(char end) throws IOException {
        StringBuffer sb = new StringBuffer();
        int ch;
        while ((ch = readByte()) != -1) {
            if (ch == end) {
                return sb.toString();
            } else {
                sb.append((char)ch);
            }
        }
        if (Config.LOGD && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "readQuotedString(): end of stream reached");
        }
        throw new IOException("readQuotedString(): end of stream reached");
    }

    private int expect(char ch) throws IOException {
        int d;
        if ((d = readByte()) != ch) {
            if (d == -1 && Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "expect(): end of stream reached");
            }
            throw new IOException(String.format("Expected %04x (%c) but got %04x (%c)", (int)ch,
                    ch, d, (char)d));
        }
        return d;
    }

    /**
     * Represents an IMAP LIST response and is also the base class for the
     * ImapResponse.
     */
    public class ImapList extends ArrayList<Object> {
        public ImapList getList(int index) {
            return (ImapList)get(index);
        }

        /** Safe version of getList() */
        public ImapList getListOrNull(int index) {
            if (index < size()) {
                Object list = get(index);
                if (list instanceof ImapList) {
                    return (ImapList) list;
                }
            }
            return null;
        }

        public String getString(int index) {
            return (String)get(index);
        }

        /** Safe version of getString() */
        public String getStringOrNull(int index) {
            if (index < size()) {
                Object string = get(index);
                if (string instanceof String) {
                    return (String) string;
                }
            }
            return null;
        }

        public InputStream getLiteral(int index) {
            return (InputStream)get(index);
        }

        public int getNumber(int index) {
            return Integer.parseInt(getString(index));
        }

        public Date getDate(int index) throws MessagingException {
            try {
                return DATE_TIME_FORMAT.parse(getString(index));
            } catch (ParseException pe) {
                throw new MessagingException("Unable to parse IMAP datetime", pe);
            }
        }

        public Object getKeyedValue(Object key) {
            for (int i = 0, count = size(); i < count; i++) {
                if (get(i).equals(key)) {
                    return get(i + 1);
                }
            }
            return null;
        }

        public ImapList getKeyedList(Object key) {
            return (ImapList)getKeyedValue(key);
        }

        public String getKeyedString(Object key) {
            return (String)getKeyedValue(key);
        }

        public InputStream getKeyedLiteral(Object key) {
            return (InputStream)getKeyedValue(key);
        }

        public int getKeyedNumber(Object key) {
            return Integer.parseInt(getKeyedString(key));
        }

        public Date getKeyedDate(Object key) throws MessagingException {
            try {
                String value = getKeyedString(key);
                if (value == null) {
                    return null;
                }
                return DATE_TIME_FORMAT.parse(value);
            } catch (ParseException pe) {
                throw new MessagingException("Unable to parse IMAP datetime", pe);
            }
        }
    }

    /**
     * Represents a single response from the IMAP server. Tagged responses will
     * have a non-null tag. Untagged responses will have a null tag. The object
     * will contain all of the available tokens at the time the response is
     * received. In general, it will either contain all of the tokens of the
     * response or all of the tokens up until the first LITERAL. If the object
     * does not contain the entire response the caller must call more() to
     * continue reading the response until more returns false.
     */
    public class ImapResponse extends ImapList {
        private boolean mCompleted;

        boolean mCommandContinuationRequested;
        String mTag;

        /*
         * Return true if this response is completely read and parsed.
         */
        public boolean completed() {
            return mCompleted;
        }
        
        /*
         * Nail down the last element that possibly is FixedLengthInputStream literal. 
         */
        public void nailDown() throws IOException {
            int last = size() - 1;
            if (last >= 0) {
                Object o = get(last);
                if (o instanceof FixedLengthInputStream) {
                    FixedLengthInputStream is = (FixedLengthInputStream) o;
                    byte[] buffer = new byte[is.available()];
                    is.read(buffer);
                    set(last, new String(buffer));
                }
            }
        }
        
        /*
         * Append all response elements to this and copy completed flag.
         */
        public void appendAll(ImapResponse other) {
            addAll(other);
            mCompleted = other.mCompleted;
        }
        
        public boolean more() throws IOException {
            if (mCompleted) {
                return false;
            }
            readTokens(this);
            return true;
        }

        // Convert * [ALERT] blah blah blah into "blah blah blah"
        public String getAlertText() {
            if (size() > 1) {
                ImapList alertList = this.getListOrNull(1);
                if (alertList != null) {
                    String responseCode = alertList.getStringOrNull(0);
                    if ("ALERT".equalsIgnoreCase(responseCode)) {
                        StringBuffer sb = new StringBuffer();
                        for (int i = 2, count = size(); i < count; i++) {
                            if (i > 2) {
                                sb.append(' ');
                            }
                            sb.append(get(i).toString());
                        }
                        return sb.toString();
                    }
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "#" + mTag + "# " + super.toString();
        }
    }

}
