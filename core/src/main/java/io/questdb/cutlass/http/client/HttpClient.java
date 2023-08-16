/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.http.client;

import io.questdb.HttpClientConfiguration;
import io.questdb.cutlass.http.HttpHeaderParser;
import io.questdb.cutlass.http.HttpRequestHeader;
import io.questdb.network.IOOperation;
import io.questdb.network.NetworkFacade;
import io.questdb.std.*;
import io.questdb.std.str.AbstractCharSink;
import io.questdb.std.str.CharSink;
import io.questdb.std.str.DirectByteCharSequence;

public abstract class HttpClient implements QuietCloseable {
    protected final NetworkFacade nf;
    private final int bufferSize;
    private final ObjectPool<DirectByteCharSequence> csPool = new ObjectPool<>(DirectByteCharSequence.FACTORY, 64);
    private final int defaultTimeout;
    private final Request request = new Request();
    protected int fd = -1;
    private long bufLo;
    private long ptr = bufLo;
    private ResponseHeaders responseHeaders;

    public HttpClient(HttpClientConfiguration configuration) {
        this.nf = configuration.getNetworkFacade();
        this.defaultTimeout = configuration.getTimeout();
        this.bufferSize = configuration.getBufferSize();
        this.bufLo = Unsafe.malloc(bufferSize, MemoryTag.NATIVE_DEFAULT);
        this.responseHeaders = new ResponseHeaders(bufLo, defaultTimeout);
    }

    @Override
    public void close() {
        disconnect();
        if (bufLo != 0) {
            Unsafe.free(bufLo, bufferSize, MemoryTag.NATIVE_DEFAULT);
            bufLo = 0;
        }
        responseHeaders = Misc.free(responseHeaders);
    }

    public void disconnect() {
        if (fd != -1) {
            nf.close(fd);
            fd = -1;
        }
    }

    public Request newRequest() {
        ptr = bufLo;
        request.state = Request.STATE_REQUEST;
        return request;
    }

    private int die(int byteCount) {
        if (byteCount < 1) {
            throw new HttpClientException("peer disconnect [errno=").errno(nf.errno()).put(']');
        }
        return byteCount;
    }

    private int recvOrDie(long addr, int timeout) {
        return recvOrDie(addr, (int) (bufferSize - (addr - bufLo)), timeout);
    }

    private int recvOrDie(long lo, int len, int timeout) {
        ioWait(timeout, IOOperation.READ);
        return die(nf.recv(fd, lo, len));
    }

    private int sendOrDie(long lo, int len, int timeout) {
        ioWait(timeout, IOOperation.WRITE);
        return die(nf.send(fd, lo, len));
    }

    protected void dieWaiting(int n) {
        if (n == 1) {
            return;
        }

        if (n == 0) {
            throw new HttpClientException("timed out [errno=").put(nf.errno()).put(']');
        }

        throw new HttpClientException("queue error [errno=").put(nf.errno()).put(']');
    }

    protected abstract void ioWait(int timeout, int op);

    protected abstract void setupIoWait();

    public interface Chunk {
        long hi();

        long lo();
    }

    private class ChunkedResponseImpl extends AbstractChunkedResponse {
        public ChunkedResponseImpl(long bufLo, int defaultTimeout) {
            super(bufLo, defaultTimeout);
        }

        @Override
        protected int recvOrDie(long buf, int timeout) {
            return HttpClient.this.recvOrDie(buf, timeout);
        }
    }

    public class Request extends AbstractCharSink {
        private static final int STATE_HEADER = 4;
        private static final int STATE_QUERY = 3;
        private static final int STATE_REQUEST = 0;
        private static final int STATE_URL = 1;
        private static final int STATE_URL_DONE = 2;
        private int state;
        private boolean urlEncode = false;

        public Request GET() {
            assert state == STATE_REQUEST;
            state = STATE_URL;
            return put("GET ");
        }

        public Request header(CharSequence name, CharSequence value) {
            assert state == STATE_QUERY || state == STATE_URL_DONE || state == STATE_HEADER;

            if (state == STATE_QUERY || state == STATE_URL_DONE) {
                put(" HTTP/1.1").crlf();
                state = STATE_HEADER;
            } else {
                crlf();
            }
            encodeUtf8(name).put(": ").encodeUtf8(value);
            return crlf();
        }

        @Override
        public Request put(CharSequence str) {
            int len = str.length();
            Chars.asciiStrCpy(str, len, ptr);
            ptr += len;
            return this;
        }

        @Override
        public CharSink put(char c) {
            Unsafe.getUnsafe().putByte(ptr, (byte) c);
            ptr++;
            return this;
        }

        @Override
        public void putUtf8Special(char c) {
            if (urlEncode) {
                putUrlEncoded(c);
            } else {
                put(c);
            }
        }

        public Request query(CharSequence name, CharSequence value) {
            assert state == STATE_URL_DONE || state == STATE_QUERY;
            if (state == STATE_URL_DONE) {
                put('?');
            } else {
                put('&');
            }
            state = STATE_QUERY;
            urlEncode = true;
            try {
                encodeUtf8(name).put('=').encodeUtf8(value);
            } finally {
                urlEncode = false;
            }
            return this;
        }

        public ResponseHeaders send(CharSequence host, int port) {
            return send(host, port, defaultTimeout);
        }

        public ResponseHeaders send(CharSequence host, int port, int timeout) {
            assert state == STATE_URL_DONE || state == STATE_QUERY || state == STATE_HEADER;
            if (fd == -1) {
                connect(host, port);
            }


            if (state == STATE_URL_DONE || state == STATE_QUERY) {
                put(" HTTP/1.1").crlf();
            }

            crlf();
            doSend(timeout);
            responseHeaders.init();
            return responseHeaders;
        }

        public Request url(CharSequence url) {
            assert state == STATE_URL;
            state = STATE_URL_DONE;
            return put(url);
        }

        private void connect(CharSequence host, int port) {
            fd = nf.socketTcp(true);
            if (fd < 0) {
                throw new HttpClientException("could not allocate a file descriptor").errno(nf.errno());
            }
            long addrInfo = nf.getAddrInfo(host, port);
            if (addrInfo == -1) {
                disconnect();
                throw new HttpClientException("could not resolve host ").put("[host=").put(host).put("]");
            }
            if (nf.connectAddrInfo(fd, addrInfo) != 0) {
                int errno = nf.errno();
                disconnect();
                nf.freeAddrInfo(addrInfo);
                throw new HttpClientException("could not connect to host ").put("[host=").put(host).put(", port=").put(port).put(", errno=").put(errno).put(']');
            }
            nf.freeAddrInfo(addrInfo);
        }

        private Request crlf() {
            String CRLF = "\r\n";
            return put(CRLF);
        }

        private void doSend(int timeout) {
            setupIoWait();
            int len = (int) (ptr - bufLo);
            if (len > 0) {
                long p = bufLo;
                do {
                    final int sent = sendOrDie(p, len, timeout);
                    if (sent > 0) {
                        p += sent;
                        len -= sent;
                    }
                } while (len > 0);
            }
        }

        private void putUrlEncoded(char c) {
            switch (c) {
                case ' ':
                    put("%20");
                    break;
                case '!':
                    put("%21");
                    break;
                case '"':
                    put("%22");
                    break;
                case '#':
                    put("%23");
                    break;
                case '$':
                    put("%24");
                    break;
                case '%':
                    put("%25");
                    break;
                case '&':
                    put("%26");
                    break;
                case '\'':
                    put("%27");
                    break;
                case '(':
                    put("%28");
                    break;
                case ')':
                    put("%29");
                    break;
                case '*':
                    put("%2A");
                    break;
                case '+':
                    put("%2B");
                    break;
                case ',':
                    put("%2C");
                    break;
                case '-':
                    put("%2D");
                    break;
                case '.':
                    put("%2E");
                    break;
                case '/':
                    put("%2F");
                    break;
                case ':':
                    put("%3A");
                    break;
                case ';':
                    put("%3B");
                    break;
                case '<':
                    put("%3C");
                    break;
                case '=':
                    put("%3D");
                    break;
                case '>':
                    put("%3E");
                    break;
                case '?':
                    put("%3F");
                    break;
                case '@':
                    put("%40");
                    break;
                case '[':
                    put("%5B");
                    break;
                case '\\':
                    put("%5C");
                    break;
                case ']':
                    put("%5D");
                    break;
                case '^':
                    put("%5E");
                    break;
                case '_':
                    put("%5F");
                    break;
                case '`':
                    put("%60");
                    break;

                case '{':
                    put("%7B");
                    break;
                case '|':
                    put("%7C");
                    break;
                case '}':
                    put("%7D");
                    break;
                default:
                    // there are symbols to escape, but those we do not tend to use at all
                    // https://www.w3schools.com/tags/ref_urlencode.ASP
                    put(c);
                    break;
            }
        }
    }

    public class ResponseHeaders implements QuietCloseable {
        private final long bufLo;
        private final AbstractChunkedResponse chunkedResponse;
        private final int defaultTimeout;
        private HttpHeaderParser headerParser = new HttpHeaderParser(4096, csPool);

        public ResponseHeaders(long bufLo, int defaultTimeout) {
            this.bufLo = bufLo;
            this.defaultTimeout = defaultTimeout;
            this.chunkedResponse = new ChunkedResponseImpl(bufLo, defaultTimeout);
        }

        public void await() {
            await(defaultTimeout);
        }

        public void await(int timeout) {
            while (headerParser.isIncomplete()) {
                final int len = recvOrDie(bufLo, timeout);
                if (len > 0) {
                    // dataLo & dataHi are boundaries of unprocessed data left in the buffer
                    chunkedResponse.begin(
                            headerParser.parse(bufLo, bufLo + len, false, true),
                            bufLo + len
                    );
                }
            }
        }

        @Override
        public void close() {
            headerParser = Misc.free(headerParser);
        }

        public ChunkedResponse getChunkedResponse() {
            return chunkedResponse;
        }

        public HttpRequestHeader header() {
            return headerParser;
        }

        public boolean isChunked() {
            if (headerParser.isIncomplete()) {
                throw new HttpClientException("http response headers not yet received");
            }
            return Chars.equalsNc("chunked", header().getHeader("Transfer-Encoding"));
        }

        // todo: rename to clear()
        private void init() {
            csPool.clear();
            headerParser.clear();
        }
    }
}
