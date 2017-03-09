/**
 * Copyright 2005-2015 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.forge.generator.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import io.fabric8.forge.generator.AttributeMapKeys;
import io.fabric8.forge.generator.cache.CacheFacade;
import io.fabric8.forge.generator.cache.CacheNames;
import io.fabric8.forge.generator.git.GitAccount;
import io.fabric8.forge.generator.git.GitProvider;
import io.fabric8.forge.generator.pipeline.AbstractDevToolsCommand;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.ServiceNames;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.api.model.ProjectList;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.utils.DomHelper;
import io.fabric8.utils.Strings;
import io.fabric8.utils.URLUtils;
import org.infinispan.Cache;
import org.jboss.forge.addon.ui.command.UICommand;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.inject.Inject;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.RedirectionException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static io.fabric8.forge.generator.kubernetes.Base64Helper.base64decode;
import static io.fabric8.project.support.BuildConfigHelper.createAndApplyBuildConfig;

/**
 * Creates the BuildConfig in OpenShift/Kubernetes so that the Jenkins build will be created
 */
public class CreateBuildConfigStep extends AbstractDevToolsCommand implements UICommand {
    protected static final String GITHUB_SCM_NAVIGATOR_ELEMENT = "org.jenkinsci.plugins.github__branch__source.GitHubSCMNavigator";

    private static final transient Logger LOG = LoggerFactory.getLogger(CreateBuildConfigStep.class);
    protected Cache<String, List<String>> namespacesCache;
    @Inject
    @WithAttributes(label = "Space", required = true, description = "The space to create the app")
    private UISelectOne<String> kubernetesSpace;
    @Inject
    @WithAttributes(label = "Trigger build", description = "Should a build be triggered immediately after import?")
    private UIInput<Boolean> triggerBuild;
    @Inject
    @WithAttributes(label = "Add CI?", description = "Should we add a Continuous Integration webhooks for Pull Requests?")
    private UIInput<Boolean> addCIWebHooks;
    @Inject
    private CacheFacade cacheManager;
    private KubernetesClient kubernetesClient;

    /**
     * Combines the job patterns.
     */
    public static String combineJobPattern(String oldPattern, String repoName) {
        if (oldPattern == null) {
            oldPattern = "";
        }
        oldPattern = oldPattern.trim();
        if (oldPattern.isEmpty()) {
            return repoName;
        }
        return oldPattern + "|" + repoName;
    }

    public void initializeUI(final UIBuilder builder) throws Exception {
        this.namespacesCache = cacheManager.getCache(CacheNames.USER_NAMESPACES);
        final String key = KubernetesClientHelper.getUserCacheKey();
        List<String> namespaces = namespacesCache.computeIfAbsent(key, k -> loadNamespaces(key));

        kubernetesSpace.setValueChoices(namespaces);
        if (!namespaces.isEmpty()) {
            kubernetesSpace.setDefaultValue(namespaces.get(0));
        }
        triggerBuild.setDefaultValue(true);
        addCIWebHooks.setDefaultValue(true);
        builder.add(kubernetesSpace);
        builder.add(triggerBuild);
        builder.add(addCIWebHooks);
    }

    private List<String> loadNamespaces(String key) {
        LOG.info("Loading user namespaces for key " + key);
        SortedSet<String> namespaces = new TreeSet<>();

        KubernetesClient kubernetes = getKubernetesClient();
        String namespace = kubernetes.getNamespace();
        OpenShiftClient openshiftClient = KubernetesClientHelper.getOpenShiftClientOrNull(kubernetes);
        if (openshiftClient != null) {
            // It is preferable to iterate on the list of projects as regular user with the 'basic-role' bound
            // are not granted permission get operation on non-existing project resource that returns 403
            // instead of 404. Only more privileged roles like 'view' or 'cluster-reader' are granted this permission.
            ProjectList list = openshiftClient.projects().list();
            if (list != null) {
                List<Project> items = list.getItems();
                if (items != null) {
                    for (Project item : items) {
                        String name = KubernetesHelper.getName(item);
                        if (Strings.isNotBlank(name)) {
                            namespaces.add(name);
                        }
                    }
                }
            }
        } else {
            NamespaceList list = kubernetes.namespaces().list();
            List<Namespace> items = list.getItems();
            if (items != null) {
                for (Namespace item : items) {
                    String name = KubernetesHelper.getName(item);
                    if (Strings.isNotBlank(name)) {
                        namespaces.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(namespaces);
    }

    @Override
    public Result execute(UIExecutionContext context) throws Exception {
        UIContext uiContext = context.getUIContext();
        Map<Object, Object> attributeMap = uiContext.getAttributeMap();

        String namespace = kubernetesSpace.getValue();
        String projectName = getProjectName(uiContext);
        String gitUrl = (String) attributeMap.get(AttributeMapKeys.GIT_URL);
        if (Strings.isNullOrBlank(gitUrl)) {
            return Results.fail("No attribute: " + AttributeMapKeys.GIT_URL);
        }
        GitAccount details = (GitAccount) attributeMap.get(AttributeMapKeys.GIT_ACCOUNT);
        if (details == null) {
            return Results.fail("No attribute: " + AttributeMapKeys.GIT_ACCOUNT);

        }
        String gitRepoNameValue = (String) attributeMap.get(AttributeMapKeys.GIT_REPO_NAME);
        Iterable<String> gitRepoNames = (Iterable<String>) attributeMap.get(AttributeMapKeys.GIT_REPO_NAMES);
        if (Strings.isNullOrBlank(gitRepoNameValue) && gitRepoNames == null) {
            return Results.fail("No attribute: " + AttributeMapKeys.GIT_REPO_NAME + " or " + AttributeMapKeys.GIT_REPO_NAMES);
        }
        List<String> gitRepoNameList = new ArrayList<>();
        if (Strings.isNotBlank(gitRepoNameValue)) {
            gitRepoNameList.add(gitRepoNameValue);
        } else {
            for (String gitRepoName : gitRepoNames) {
                gitRepoNameList.add(gitRepoName);
            }
        }
/*
        String gitOrganisation = (String) attributeMap.get(AttributeMapKeys.GIT_ORGANISATION);
        if (Strings.isNullOrBlank(gitOrganisation)) {
            return Results.fail("No attribute: " + AttributeMapKeys.GIT_ORGANISATION);
        }
*/
        String gitOwnerName = (String) attributeMap.get(AttributeMapKeys.GIT_OWNER_NAME);
        if (Strings.isNullOrBlank(gitOwnerName)) {
            gitOwnerName = details.getUsername();
        }
        GitProvider gitProvider = (GitProvider) attributeMap.get(AttributeMapKeys.GIT_PROVIDER);
        if (gitProvider == null) {
            return Results.fail("No attribute: " + AttributeMapKeys.GIT_PROVIDER);
        }

        Map<String, String> annotations = new HashMap<>();
        // lets add the annotations so that it looks like its generated by jenkins-sync plugin to minimise duplication
        annotations.put("jenkins.openshift.org/generated-by", "jenkins");
        annotations.put("jenkins.openshift.org/job-path", "" + gitOwnerName + "/" + gitRepoNameValue + "/master");

        KubernetesClient kubernetes = getKubernetesClient();
        createAndApplyBuildConfig(kubernetes, namespace, projectName, gitUrl, annotations);

        String message = "Created OpenShift Build";
        if (addCIWebHooks.getValue()) {
            boolean first = true;
            for (String gitRepoName : gitRepoNameList) {
                try {
                    String discoveryNamespace = KubernetesClientHelper.getDiscoveryNamespace(kubernetes);
                    String jenkinsUrl;
                    try {
                        jenkinsUrl = KubernetesHelper.getServiceURL(kubernetes, ServiceNames.JENKINS, discoveryNamespace, "https", true);
                    } catch (Exception e) {
                        return Results.fail("Failed to find Jenkins URL: " + e, e);
                    }

                    String botServiceAccount = "cd-bot";
                    String botSecret = findBotSecret(discoveryNamespace, botServiceAccount);
                    if (Strings.isNullOrBlank(botSecret)) {
                        botSecret = "secret101";
                    }
                    String oauthToken = kubernetes.getConfiguration().getOauthToken();
                    System.out.println("Using OpenShift token: " + oauthToken);
                    String authHeader = "Bearer " + oauthToken;

                    String webhookUrl = URLUtils.pathJoin(jenkinsUrl, "/github-webhook/");

                    try {
                        if (first) {
                            first = false;
                            ensureJenkinsCDCredentialCreated(gitOwnerName, details.tokenOrPassword(), jenkinsUrl, authHeader);
                        }

                        ensureJenkinsCDOrganisationJobCreated(jenkinsUrl, oauthToken, authHeader, gitOwnerName, gitRepoName);

                        registerGitWebHook(details, webhookUrl, gitOwnerName, gitRepoName, botSecret);
                    } catch (Exception e) {
                        LOG.error("Failed: " + e, e);
                        return Results.fail(e.getMessage(), e);
                    }
                    message += " and CI webhooks";
                } catch (Exception e) {
                    LOG.error("Failed to create CI webhooks: " + e, e);
                    return Results.fail("Failed to create CI webhooks: " + e, e);
                }
            }
        }
        return Results.success(message);
    }

    private void registerGitWebHook(GitAccount details, String webhookUrl, String gitOwnerName, String gitRepoName, String botSecret) throws IOException {

        // TODO move this logic into the GitProvider!!!
        String body = "{\"name\": \"web\",\"active\": true,\"events\": [\"*\"],\"config\": {\"url\": \"" + webhookUrl + "\",\"insecure_ssl\":\"1\"," +
                "\"content_type\": \"json\",\"secret\":\"" + botSecret + "\"}}";

        String authHeader = details.mandatoryAuthHeader();
        String createWebHookUrl = URLUtils.pathJoin("https://api.github.com/repos/", gitOwnerName, gitRepoName, "/hooks");
/*
        TODO JAX-RS doesn't work so lets use trusty java.net.URL instead ;)
        
        try {
            Client client = createSecureClient();
            return invokeRequestWithRedirectResponse(client, createWebHookUrl,
                    target -> target.request(MediaType.APPLICATION_JSON).
                            header("Authorization", authHeader).
                            post(Entity.json(new ByteArrayInputStream(body.getBytes())), Response.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create the github web hook at: " + createWebHookUrl + ". " + e, e);
        }
*/

        HttpURLConnection connection = null;
        try {
            URL url = new URL(createWebHookUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", MediaType.APPLICATION_JSON);
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON);
            connection.setRequestProperty("Authorization", authHeader);
            connection.setDoOutput(true);

            OutputStreamWriter out = new OutputStreamWriter(
                    connection.getOutputStream());
            out.write(body);

            out.close();
            int status = connection.getResponseCode();
            String message = connection.getResponseMessage();
            LOG.info("Got response code from github " + createWebHookUrl + " status: " + status + " message: " + message);
            if (status < 200 || status >= 300) {
                LOG.error("Failed to create the github web hook at: " + createWebHookUrl + ". Status: " + status + " message: " + message);
                throw new IllegalStateException("Failed to create the github web hook at: " + createWebHookUrl + ". Status: " + status + " message: " + message);
            }
        } catch (Exception e) {
            LOG.error("Failed to create the github web hook at: " + createWebHookUrl + ". " + e, e);
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Finds the secret token we should use for the web hooks
     */
    private String findBotSecret(String discoveryNamespace, String botServiceAccount) {
        KubernetesClient kubernetes = getKubernetesClient();
        SecretList list = kubernetes.secrets().inNamespace(discoveryNamespace).list();
        if (list != null) {
            List<Secret> items = list.getItems();
            if (items != null) {
                for (Secret item : items) {
                    String name = KubernetesHelper.getName(item);
                    if (name.startsWith(botServiceAccount + "-token-")) {
                        Map<String, String> data = item.getData();
                        if (data != null) {
                            String token = data.get("token");
                            if (token != null) {
                                return base64decode(token);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Triggers the given jenkins job via its URL.
     *
     * @param authHeader
     * @param jobUrl the URL to the jenkins job
     * @param triggerUrl can be null or empty and the default triggerUrl will be used
     */
    protected void triggerJenkinsWebHook(String token, String authHeader, String jobUrl, String triggerUrl, boolean post) {
        if (Strings.isNullOrBlank(triggerUrl)) {
            //triggerUrl = URLUtils.pathJoin(jobUrl, "/build?token=" + token);
            triggerUrl = URLUtils.pathJoin(jobUrl, "/build?delay=0");
        }
        // lets check if this build is already running in which case do nothing
        String lastBuild = URLUtils.pathJoin(jobUrl, "/lastBuild/api/json");
        JsonNode lastBuildJson = parseLastBuildJson(authHeader, lastBuild);
        JsonNode building = null;
        if (lastBuildJson != null && lastBuildJson.isObject()) {
            building = lastBuildJson.get("building");
            if (building != null && building.isBoolean()) {
                if (building.booleanValue()) {
                    LOG.info("Build is already running so lets not trigger another one!");
                    return;
                }
            }
        }
        LOG.info("Got last build JSON: " + lastBuildJson + " building: " + building);

        LOG.info("Triggering Jenkins build: " + triggerUrl);

        Client client = createClient();
        try {
            Response response = client.target(triggerUrl).
                    request().
                    header("Authorization", authHeader).
                    post(Entity.text(null), Response.class);

            int status = response.getStatus();
            String message = null;
            Response.StatusType statusInfo = response.getStatusInfo();
            if (statusInfo != null) {
                message = statusInfo.getReasonPhrase();
            }
            String extra = "";
            if (status == 302) {
                extra = " Location: " + response.getLocation();
            }
            LOG.info("Got response code from Jenkins: " + status + " message: " + message + " from URL: " + triggerUrl + extra);
            if (status <= 200 || status > 302) {
                LOG.error("Failed to trigger job " + triggerUrl + ". Status: " + status + " message: " + message);
            }
        } finally {
            client.close();
        }
    }

    protected JsonNode parseLastBuildJson(String authHeader, String urlText) {
        Client client = createClient();
        try {
            Response response = client.target(urlText).
                    request().
                    header("Authorization", authHeader).
                    post(Entity.text(null), Response.class);

            int status = response.getStatus();
            String message = null;
            Response.StatusType statusInfo = response.getStatusInfo();
            if (statusInfo != null) {
                message = statusInfo.getReasonPhrase();
            }
            LOG.info("Got response code from Jenkins: " + status + " message: " + message + " from URL: " + urlText);
            if (status <= 200 || status >= 300) {
                LOG.error("Failed to trigger job " + urlText + ". Status: " + status + " message: " + message);
            } else {
                String json = response.readEntity(String.class);
                if (Strings.isNotBlank(json)) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    try {
                        return objectMapper.reader().readTree(json);
                    } catch (IOException e) {
                        LOG.warn("Failed to parse JSON: " + e, e);
                    }
                }
            }
        } finally {
            client.close();
        }
        return null;
/*
        HttpURLConnection connection = null;
        String message = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", authHeader);

            int status = connection.getResponseCode();
            message = connection.getResponseMessage();
            LOG.info("Got response code from URL: " + url + " " + status + " message: " + message);
            if (status != 200 || Strings.isNullOrBlank(message)) {
                LOG.debug("Failed to load URL " + url + ". Status: " + status + " message: " + message);
            } else {
                ObjectMapper objectMapper = new ObjectMapper();
                return objectMapper.reader().readTree(message);
            }
        } catch (Exception e) {
            LOG.debug("Failed to load URL " + urlText + ". " + e, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;*/
    }

    private Response ensureJenkinsCDCredentialCreated(String gitUserName, String gitToken, String jenkinsUrl, String authHeader) {
        String answer = null;

        LOG.info("Creating Jenkins fabric8 credentials for github user name: " + gitUserName);

        //String createUrl = URLUtils.pathJoin(jenkinsUrl, "/credentials/store/system/domain/_/createCredentials");
        String createUrl = URLUtils.pathJoin(jenkinsUrl, "/credentials/store/system/domain/_/");
        /*
        String getUrl = URLUtils.pathJoin(jenkinsUrl, "/credentials/store/system/domain/_/credentials/fabric8");

        Not sure we need to check it it already exists...

        try {
            answer = client.target(getUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", authHeader)
                    .get(String.class);
        } catch (Exception e) {
            LOG.warn("Caught probably expected error querying URL: " + getUrl + ". " + e, e);
        }
*/
        Response response = null;

        if (answer == null) {
            String json = "{\n" +
                    "  \"\": \"0\",\n" +
                    "  \"credentials\": {\n" +
                    "    \"scope\": \"GLOBAL\",\n" +
                    "    \"id\": \"fabric8\",\n" +
                    "    \"username\": \"" + gitUserName + "\",\n" +
                    "    \"password\": \"" + gitToken + "\",\n" +
                    "    \"description\": \"fabric8\",\n" +
                    "    \"$class\": \"com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl\"\n" +
                    "  }\n" +
                    "}";


            Form form = new Form();
            form.param("json", json);

            try {
                return invokeRequestWithRedirectResponse(createUrl,
                        target -> target.request(MediaType.APPLICATION_JSON).
                                header("Authorization", authHeader).
                                post(Entity.entity(form, MediaType.APPLICATION_FORM_URLENCODED), Response.class));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create the fabric8 credentials in Jenkins at the URL " + createUrl + ". " + e, e);
            }
        }
        return response;
    }


    private Response ensureJenkinsCDOrganisationJobCreated(String jenkinsUrl, String oauthToken, String authHeader, String gitOwnerName, String gitRepoName) {
        String jobUrl = URLUtils.pathJoin(jenkinsUrl, "/job/" + gitOwnerName);
        String triggerUrl = URLUtils.pathJoin(jobUrl, "/build?delay=0");
        String getUrl = URLUtils.pathJoin(jobUrl, "/config.xml");
        String createUrl = URLUtils.pathJoin(jenkinsUrl, "/createItem?name=" + gitOwnerName);

        Document document = null;
        try {
            Response response = invokeRequestWithRedirectResponse(getUrl,
                    target -> target.request(MediaType.TEXT_XML).
                            header("Authorization", authHeader).
                            get(Response.class));
            document = response.readEntity(Document.class);
            if (document == null) {
                document = parseEntityAsXml(response.readEntity(String.class));
            }
        } catch (Exception e) {
            LOG.warn("Failed to get gitub org job at " + getUrl + ". Probably does not exist? " + e, e);
        }

        boolean create = false;
        if (document == null || getGithubScmNavigatorElement(document) == null) {
            create = true;
            document = parseGitHubOrgJobConfig();
            if (document == null) {
                throw new IllegalStateException("Cannot parse the template github org job XML!");
            }
        }

        setGithubOrgJobOwnerAndRepo(document, gitOwnerName, gitRepoName);
/*
        try {
            LOG.info("Generating XML: " + DomHelper.toXml(document));
        } catch (TransformerException e) {
            // ignore
        }
*/
        final Entity entity = Entity.entity(document, MediaType.TEXT_XML);
        Response answer;
        if (create) {
            try {
                answer = invokeRequestWithRedirectResponse(createUrl,
                        target -> target.request(MediaType.TEXT_XML).
                                header("Authorization", authHeader).
                                post(entity, Response.class));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create the GitHub Org Job at " + createUrl + ". " + e, e);
            }
        } else {
            try {
                answer =  invokeRequestWithRedirectResponse(getUrl,
                        target -> target.request(MediaType.TEXT_XML).
                                header("Authorization", authHeader).
                                post(entity, Response.class));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to update the GitHub Org Job at " + getUrl + ". " + e, e);
            }
        }

        LOG.info("Triggering the job " + jobUrl);
        try {
            triggerJenkinsWebHook(oauthToken, authHeader, jobUrl, triggerUrl, true);
/*
            invokeWithDisabledTrustManager(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    triggerJenkinsWebHook(oauthToken, authHeader, jobUrl, triggerUrl, true);
                    return null;
                }
            });
*/
        } catch (Exception e) {
            LOG.error("Failed to trigger jenkins job at " + triggerUrl + ". " + e, e);
        }
        return answer;
    }

    private void setGithubOrgJobOwnerAndRepo(Document doc, String gitOwnerName, String gitRepoName) {
        Element githubNavigator = getGithubScmNavigatorElement(doc);
        if (githubNavigator == null) {
            new IllegalArgumentException("No element <" + GITHUB_SCM_NAVIGATOR_ELEMENT + "> found in the github organisation job!");
        }

        Element repoOwner = mandatoryFirstChild(githubNavigator, "repoOwner");
        Element pattern = mandatoryFirstChild(githubNavigator, "pattern");

        String newPattern = combineJobPattern(pattern.getTextContent(), gitRepoName);
        boolean updated = setElementText(repoOwner, gitOwnerName);
        if (setElementText(pattern, newPattern)) {
            updated = true;
        }
    }

    protected Element getGithubScmNavigatorElement(Document doc) {
        Element githubNavigator = null;
        Element rootElement = doc.getDocumentElement();
        if (rootElement != null) {
            NodeList githubNavigators = rootElement.getElementsByTagName(GITHUB_SCM_NAVIGATOR_ELEMENT);
            for (int i = 0, size = githubNavigators.getLength(); i < size; i++) {
                Node item = githubNavigators.item(i);
                if (item instanceof Element) {
                    Element element = (Element) item;
                    githubNavigator = element;
                    break;
                }
            }
        }
        return githubNavigator;
    }

    public static <T> T invokeWithDisabledTrustManager(Callable<T> callback) throws Exception {
        SSLSocketFactory defaultSSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };

            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

            return callback.call();
        } finally {
            HttpsURLConnection.setDefaultHostnameVerifier(defaultHostnameVerifier);
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultSSLSocketFactory);
        }
    }

    protected Response invokeRequestWithRedirectResponse(String url, Function<WebTarget, Response> callback) {
        Client client = createClient();
        WebTarget target = client.target(url);
        boolean redirected = false;
        Response response = null;
        for (int i = 0, retries = 2; i < retries; i++) {
            try {
                response = callback.apply(target);
                int status = response.getStatus();
                String reasonPhrase = "";
                Response.StatusType statusInfo = response.getStatusInfo();
                if (statusInfo != null) {
                    reasonPhrase = statusInfo.getReasonPhrase();
                }
                LOG.info("Response from " + url + " is " + status + " " + reasonPhrase);
                if (status == 302) {
                    if (redirected) {
                        LOG.warn("Failed to process " + url + " and got status: " + status + " " + reasonPhrase, response);
                        throw new WebApplicationException("Failed to process " + url + " and got status: " + status + " " + reasonPhrase, response);
                    }
                    redirected = true;
                    URI uri = response.getLocation();
                    if (uri == null) {
                        LOG.warn("Failed to process " + url + " and got status: " + status + " " + reasonPhrase + " but no location header!", response);
                        throw new WebApplicationException("Failed to process " + url + " and got status: " + status + " " + reasonPhrase + " but no location header!", response);
                    }
                    url = uri.toString();
                    target = client.target(uri);
                } else if (status < 200 || status >= 300) {
                    LOG.warn("Failed to process " + url + " and got status: " + status + " " + reasonPhrase, response);
                    throw new WebApplicationException("Failed to process " + url + " and got status: " + status + " " + reasonPhrase, response);
                } else {
                    return response;
                }
            } catch (RedirectionException redirect) {
                if (redirected) {
                    throw redirect;
                }
                redirected = true;
                URI uri = redirect.getLocation();
                url = uri.toString();
                target = client.target(uri);
            } finally {
                client.close();
            }
        }
        return response;
    }

    private Client createSecureClient() {
        return ClientBuilder.newClient();
    }

    private Client createClient() {
        ResteasyClientBuilder clientBuilder = new ResteasyClientBuilder();
        clientBuilder.disableTrustManager();
        clientBuilder.hostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                return true;
            }
        });
/*
        Configuration configuration = new ClientConfiguration();
        Client client = ClientBuilder.newClient(configuration);

        // lets disable SSL cert issues:
        HTTPConduit conduit = WebClient.getConfig(client).getHttpConduit();

        TLSClientParameters params = conduit.getTlsClientParameters();

        if (params == null) {
            params = new TLSClientParameters();
            conduit.setTlsClientParameters(params);
        }
        params.setTrustManagers(new TrustManager[]{new TrustEverythingSSLTrustManager()});
        params.setDisableCNCheck(true);
*/
        return clientBuilder.build();
    }

    /**
     * Updates the element content if its different and returns true if it was changed
     */
    private boolean setElementText(Element element, String value) {
        String textContent = element.getTextContent();
        if (Objects.equal(value, textContent)) {
            return false;
        }
        element.setTextContent(value);
        return true;
    }

    /**
     * Returns the first child of the given element with the name or throws an exception
     */
    private Element mandatoryFirstChild(Element element, String name) {
        Element child = DomHelper.firstChild(element, name);
        if (child == null) {
            throw new IllegalArgumentException("The element <" + element.getTagName() + "> should have at least one child called <" + name + ">");
        }
        return child;
    }


    public KubernetesClient getKubernetesClient() {
        if (kubernetesClient == null) {
            kubernetesClient = KubernetesClientHelper.createKubernetesClientForUser();
        }
        return kubernetesClient;
    }

    private Document parseEntityAsXml(String entity) throws ParserConfigurationException, IOException, SAXException {
        if (entity == null) {
            return null;
        }
        DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return documentBuilder.parse(new ByteArrayInputStream(entity.getBytes()));
    }


    public Document parseGitHubOrgJobConfig() {
        String templateName = "github-org-job-config.xml";
        URL url = getClass().getResource(templateName);
        if (url == null) {
            LOG.error("Could not load " + templateName + " on the classpath!");
        } else {
            try {
                DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                return documentBuilder.parse(url.toString());
            } catch (Exception e) {
                LOG.error("Failed to load template " + templateName + " from " + url + ". " + e, e);
            }
        }
        return null;
    }
}
