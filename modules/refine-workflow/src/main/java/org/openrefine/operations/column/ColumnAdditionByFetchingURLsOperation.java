/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package org.openrefine.operations.column;

import static com.google.common.base.Strings.isNullOrEmpty;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;

import org.openrefine.browsing.EngineConfig;
import org.openrefine.expr.EvalError;
import org.openrefine.model.Cell;
import org.openrefine.model.ColumnInsertion;
import org.openrefine.model.ColumnModel;
import org.openrefine.model.Record;
import org.openrefine.model.Row;
import org.openrefine.model.RowInRecordMapper;
import org.openrefine.model.changes.ChangeContext;
import org.openrefine.operations.ExpressionBasedOperation;
import org.openrefine.operations.OnError;
import org.openrefine.operations.exceptions.OperationException;
import org.openrefine.overlay.OverlayModel;
import org.openrefine.util.HttpClient;

/**
 * Adds a column by fetching URLs generated by an expression, based on a column.
 */
public class ColumnAdditionByFetchingURLsOperation extends ExpressionBasedOperation {

    public static final class HttpHeader implements Serializable {

        private static final long serialVersionUID = -7546898562925960196L;
        @JsonProperty("name")
        final public String name;
        @JsonProperty("value")
        final public String value;

        @JsonCreator
        public HttpHeader(
                @JsonProperty("name") String name,
                @JsonProperty("value") String value) {
            this.name = name;
            this.value = value;
        }
    }

    final protected String _baseColumnName;
    final protected String _urlExpression;
    final protected OnError _onError;

    final protected String _newColumnName;
    final protected int _columnInsertIndex;
    final protected int _delay;
    final protected boolean _cacheResponses;
    final protected List<HttpHeader> _httpHeadersJson;

    @JsonCreator
    public ColumnAdditionByFetchingURLsOperation(
            @JsonProperty("engineConfig") EngineConfig engineConfig,
            @JsonProperty("baseColumnName") String baseColumnName,
            @JsonProperty("urlExpression") String urlExpression,
            @JsonProperty("onError") OnError onError,
            @JsonProperty("newColumnName") String newColumnName,
            @JsonProperty("columnInsertIndex") int columnInsertIndex,
            @JsonProperty("delay") int delay,
            @JsonProperty("cacheResponses") boolean cacheResponses,
            @JsonProperty("httpHeadersJson") List<HttpHeader> httpHeadersJson) {
        super(engineConfig, urlExpression, baseColumnName, onError, 0);
        _forceEagerEvaluation = true;
        _changeDataId = "urls";

        _baseColumnName = baseColumnName;
        _urlExpression = urlExpression;
        _onError = onError;

        _newColumnName = newColumnName;
        _columnInsertIndex = columnInsertIndex;

        _delay = delay;
        _cacheResponses = cacheResponses;
        _httpHeadersJson = httpHeadersJson;

        List<Header> headers = new ArrayList<Header>();
        if (_httpHeadersJson != null) {
            for (HttpHeader header : _httpHeadersJson) {
                if (!isNullOrEmpty(header.name) && !isNullOrEmpty(header.value)) {
                    headers.add(new BasicHeader(header.name, header.value));
                }
            }
        }

    }

    @Override
    public List<ColumnInsertion> getColumnInsertions() {
        return Collections.singletonList(
                ColumnInsertion.builder()
                        .withName(_newColumnName)
                        .withInsertAt(_baseColumnName)
                        .build());
    }

    @Override
    public boolean persistResults() {
        return true;
    }

    @Override
    protected RowInRecordMapper getPositiveRowMapper(ColumnModel columnModel, Map<String, OverlayModel> overlayModels,
            long estimatedRowCount, ChangeContext context) throws OperationException {
        RowInRecordMapper parentMapper = super.getPositiveRowMapper(columnModel, overlayModels, estimatedRowCount, context);

        return new URLFetchingChangeProducer(_onError, _httpHeadersJson, _cacheResponses, _delay, parentMapper);
    }

    @JsonProperty("newColumnName")
    public String getNewColumnName() {
        return _newColumnName;
    }

    @JsonProperty("columnInsertIndex")
    public int getColumnInsertIndex() {
        return _columnInsertIndex;
    }

    @JsonProperty("baseColumnName")
    public String getBaseColumnName() {
        return _baseColumnName;
    }

    @JsonProperty("urlExpression")
    public String getUrlExpression() {
        return _urlExpression;
    }

    @JsonProperty("onError")
    public OnError getOnError() {
        return _onError;
    }

    @JsonProperty("delay")
    public int getDelay() {
        return _delay;
    }

    @JsonProperty("httpHeadersJson")
    public List<HttpHeader> getHttpHeadersJson() {
        return _httpHeadersJson;
    }

    @JsonProperty("cacheResponses")
    public boolean getCacheResponses() {
        return _cacheResponses;
    }

    @Override
    public String getDescription() {
        return "Create column " + _newColumnName +
                " at index " + _columnInsertIndex +
                " by fetching URLs based on column " + _baseColumnName +
                " using expression " + _urlExpression;
    }

    protected static class URLFetchingChangeProducer extends RowInRecordMapper {

        private static final long serialVersionUID = 131571544240263338L;
        final protected OnError _onError;
        final protected List<HttpHeader> _httpHeaders;
        final protected boolean _cacheResponses;
        final protected int _delay;
        final protected RowInRecordMapper _evaluatingMapper;
        // initialized lazily for serializability
        transient private LoadingCache<String, Serializable> _urlCache;
        transient private HttpClient _httpClient;
        transient private Header[] _headers;

        protected URLFetchingChangeProducer(
                OnError onError,
                List<HttpHeader> httpHeaders,
                boolean cacheResponses,
                int delay,
                RowInRecordMapper evaluatingMapper) {
            _onError = onError;
            _cacheResponses = cacheResponses;
            _httpHeaders = httpHeaders != null ? httpHeaders : Collections.emptyList();
            _delay = delay;
            _evaluatingMapper = evaluatingMapper;
            _headers = null;
            _httpClient = null;
        }

        protected HttpClient getHttpClient() {
            if (_httpClient != null) {
                return _httpClient;
            }

            _httpClient = new HttpClient(_delay);
            return _httpClient;
        }

        protected Header[] getHttpHeaders() {
            if (_headers != null) {
                return _headers;
            }
            List<Header> headers = new ArrayList<>();
            if (_httpHeaders != null) {
                for (HttpHeader header : _httpHeaders) {
                    if (!isNullOrEmpty(header.name) && !isNullOrEmpty(header.value)) {
                        headers.add(new BasicHeader(header.name, header.value));
                    }
                }
            }
            _headers = (Header[]) headers.toArray(new Header[headers.size()]);
            return _headers;
        }

        @Override
        public Row call(Record record, long rowId, Row row) {
            return new Row(Collections.singletonList(computeCell(record, rowId, row)), row.flagged, row.starred);
        }

        public Cell computeCell(Record record, long rowId, Row row) {
            Cell urlCell = _evaluatingMapper.call(record, rowId, row).getCell(0);
            if (urlCell == null || urlCell.value instanceof EvalError || urlCell.value == null) {
                return urlCell;
            }
            String urlString = urlCell.value.toString();
            Serializable response = null;
            if (_cacheResponses) {
                response = cachedFetch(urlString);
            } else {
                response = fetch(urlString);
            }

            if (response != null) {
                return new Cell(response, null);
            }
            return null;
        }

        protected LoadingCache<String, Serializable> getCache() {
            if (_urlCache != null) {
                return _urlCache;
            }
            _urlCache = CacheBuilder.newBuilder()
                    .maximumSize(2048)
                    .expireAfterWrite(10, TimeUnit.MINUTES)
                    .build(
                            new CacheLoader<String, Serializable>() {

                                public Serializable load(String urlString) throws Exception {
                                    Serializable result = fetch(urlString);
                                    try {
                                        // Always sleep for the delay, no matter how long the
                                        // request took. This is more responsible than subtracting
                                        // the time spend requesting the URL, because it naturally
                                        // slows us down if the server is busy and takes a long time
                                        // to reply.
                                        if (_delay > 0) {
                                            Thread.sleep(_delay);
                                        }
                                    } catch (InterruptedException e) {
                                        result = null;
                                    }

                                    if (result == null) {
                                        // the load method should not return any null value
                                        throw new Exception("null result returned by fetch");
                                    }
                                    return result;
                                }
                            });
            return _urlCache;
        }

        Serializable cachedFetch(String urlString) {
            try {
                return getCache().get(urlString);
            } catch (Exception e) {
                return null;
            }
        }

        Serializable fetch(String urlString) {
            try { // HttpClients.createDefault()) {
                try {
                    Header[] headers = getHttpHeaders();
                    return getHttpClient().getAsString(urlString, headers);
                } catch (IOException e) {
                    return _onError == OnError.StoreError ? new EvalError(e) : null;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return _onError == OnError.StoreError ? new EvalError(e.getMessage()) : null;
            }
        }

        @Override
        public boolean preservesRecordStructure() {
            return _evaluatingMapper.preservesRecordStructure();
        }

    }

    @Override
    protected boolean preservesRecordStructure(ColumnModel columnModel) {
        return true;
    }

}
