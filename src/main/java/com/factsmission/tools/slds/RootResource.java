package com.factsmission.tools.slds;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import org.apache.clerezza.commons.rdf.Graph;
import org.apache.clerezza.commons.rdf.IRI;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.ontologies.RDF;
import org.apache.clerezza.rdf.utils.GraphNode;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;

@Path("")
public class RootResource {

    

    private final GraphNode config;

    RootResource(GraphNode config) {
        this.config = config;
    }

    @GET
    @Path("{path : .*}")
    public Graph getResourceDescription(@Context HttpHeaders httpHeaders, @Context UriInfo uriInfo) throws IOException {
        final URI requestUri = uriInfo.getRequestUri();
        final String hostHeader = httpHeaders.getRequestHeader("Host").get(0);
        final int hostHeaderSeparator = hostHeader.indexOf(':');
        final String host = hostHeaderSeparator > -1 ? 
                hostHeader.substring(0, hostHeaderSeparator)
                : hostHeader;
        final URI fixedUri = UriBuilder.fromUri(requestUri).host(host).build();
        IRI resource = new IRI(fixedUri.toString());
        return getGraphFor(resource);
    }

    protected CloseableHttpClient createHttpClient() {
        try {
            final HttpClientBuilder hcb = HttpClientBuilder.create();
            final CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(AuthScope.ANY, getCredentials());
            SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, new TrustStrategy() {
                @Override
                public boolean isTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                    return true;
                }
            }).build();
            hcb.setSSLContext(sslContext);
            return hcb.setDefaultCredentialsProvider(credsProvider).build();
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected UsernamePasswordCredentials getCredentials() {
        return new UsernamePasswordCredentials(getUserName(), getPassword());
    }
    
    private String getUserName() {
        return getSparqlEndpoint().getLiterals(SLDS.userName).next().getLexicalForm();
    }

    private String getPassword() {
        return getSparqlEndpoint().getLiterals(SLDS.password).next().getLexicalForm();
    }

    @GET
    @Path("debug")
    public Object debug() throws ParseException, IOException {
        return System.getProperties();
    }

    protected GraphNode getSparqlEndpoint() {
        return config.getObjectNodes(SLDS.sparqlEndpoint).next();
    }
    
    protected IriTranslator getIriTranslator() {
        if (config.getObjectNodes(SLDS.iriTranslators).hasNext()) {
            final GraphNode next = config.getObjectNodes(SLDS.iriTranslators).next();
            return getIriTranslatorFromList(next);
        } else {
            return new NillIriTranslator();
        }
    }

    private IriTranslator getIriTranslatorFromList(GraphNode list) {
        if (list.getNode().equals(RDF.nil)) {
            return new NillIriTranslator();
        }
        return new ChainedIriTranslator(
                getIriTranslator(list.getObjectNodes(RDF.first).next()),
                getIriTranslatorFromList(list.getObjectNodes(RDF.rest).next()));
    }
    
    private IriTranslator getIriTranslator(GraphNode node) {
        return new IriNamespaceTranslator(node.getLiterals(SLDS.backendPrefix).next().getLexicalForm(), 
                    node.getLiterals(SLDS.frontendPrefix).next().getLexicalForm());
    }
    
    protected Graph getGraphFor(IRI resource) throws IOException {
        IRI effectiveResource = getIriTranslator().reverse().translate(resource);
        final String query = getQuery(effectiveResource);
        return runQuery(query);
    }

    protected Graph runQuery(final String query) throws IOException {
        try (CloseableHttpClient httpClient = createHttpClient()) {
            final HttpPost httpPost = new HttpPost(((IRI)getSparqlEndpoint().getNode()).getUnicodeString());            
            System.out.println(query);
            httpPost.setEntity(new StringEntity(query, ContentType.create("application/sparql-query", "utf-8")));
            System.out.println(System.currentTimeMillis());
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                System.out.println(System.currentTimeMillis());
                if (response.getStatusLine().getStatusCode() >= 400) {
                    throw new IOException(response.getStatusLine().getReasonPhrase());
                }
                byte[] responseBody = EntityUtils.toByteArray(response.getEntity());
                return getIriTranslator().translate(Parser.getInstance()
                        .parse(new ByteArrayInputStream(responseBody),
                                response.getFirstHeader("Content-Type").getValue()));
            }
        }
    }

    protected static String getQuery(IRI resource) {
        return "DESCRIBE <"+resource.getUnicodeString()+">";
    }

    


    

}
