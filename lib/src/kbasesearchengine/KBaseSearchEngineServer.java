package kbasesearchengine;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonServerMethod;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.common.service.JsonServerSyslog;
import us.kbase.common.service.RpcContext;

//BEGIN_HEADER

import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.http.HttpHost;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import kbasesearchengine.authorization.AccessGroupCache;
import kbasesearchengine.authorization.AccessGroupProvider;
import kbasesearchengine.authorization.WorkspaceAccessGroupProvider;
import kbasesearchengine.common.GUID;
import kbasesearchengine.events.handler.CloneableWorkspaceClientImpl;
import kbasesearchengine.events.handler.WorkspaceEventHandler;
import kbasesearchengine.main.LineLogger;
import kbasesearchengine.main.SearchInterface;
import kbasesearchengine.main.SearchMethods;
import kbasesearchengine.main.SearchVersion;
import kbasesearchengine.main.NarrativeInfoDecorator;
import kbasesearchengine.search.ElasticIndexingStorage;
import kbasesearchengine.system.FileLister;
import kbasesearchengine.system.ObjectTypeParsingRulesFileParser;
import kbasesearchengine.system.TypeFileStorage;
import kbasesearchengine.system.TypeStorage;
import kbasesearchengine.system.TypeMappingParser;
import kbasesearchengine.system.YAMLTypeMappingParser;
import us.kbase.auth.AuthConfig;
import us.kbase.auth.ConfigurableAuthService;
import us.kbase.workspace.WorkspaceClient;
import kbasesearchengine.common.FileUtil;
//END_HEADER

/**
 * <p>Original spec-file module name: KBaseSearchEngine</p>
 * <pre>
 * A KBase module: KBaseSearchEngine
 * </pre>
 */
public class KBaseSearchEngineServer extends JsonServerServlet {
    private static final long serialVersionUID = 1L;
    private static final String version = "";
    private static final String gitUrl = "";
    private static final String gitCommitHash = "";

    //BEGIN_CLASS_HEADER
    
    // TODO TEST add integration test that runs server
    
    private final SearchInterface search;
    
    private void quietLoggers() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.INFO);
    }
    //END_CLASS_HEADER

    public KBaseSearchEngineServer() throws Exception {
        super("KBaseSearchEngine");
        //BEGIN_CONSTRUCTOR
        quietLoggers();
        URL wsUrl = new URL(config.get("workspace-url"));
        String tokenStr = config.get("indexer-token");
        final String authURL = getAuthUrlFromConfig(config);
        final AuthConfig c = new AuthConfig();
        if ("true".equals(getAuthAllowInsecureFromConfig(config))) {
            c.withAllowInsecureURLs(true);
        }
        c.withKBaseAuthServerURL(new URL(authURL));
        ConfigurableAuthService auth = new ConfigurableAuthService(c);
        AuthToken kbaseIndexerToken = auth.validateToken(tokenStr);
        String elasticHost = config.get("elastic-host");
        int elasticPort = Integer.parseInt(config.get("elastic-port"));
        String esUser = config.get("elastic-user");
        String esPassword = config.get("elastic-password");
        HttpHost esHostPort = new HttpHost(elasticHost, elasticPort);
        final Path typesDir = Paths.get(config.get("types-dir"));
        final Path mappingsDir = Paths.get(config.get("type-mappings-dir"));
        File tempDir = new File(config.get("scratch"));
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        String esIndexPrefix = config.get("elastic-namespace") + ".";
        String adminsText = config.get("admins");
        Set<String> admins = new LinkedHashSet<>();
        if (adminsText != null) {
            admins.addAll(Arrays.asList(adminsText.split(",")).stream().map(String::trim).collect(
                    Collectors.toList()));
        }
        File logFile = new File(tempDir, "log_" + System.currentTimeMillis() + ".txt");
        PrintWriter logPw = new PrintWriter(logFile);
        final LineLogger logger = new LineLogger() {
            @Override
            public void logInfo(String line) {
                logPw.println(line);
                logPw.flush();
            }
            @Override
            public void logError(String line) {
                logPw.println(line);
                logPw.flush();
            }
            @Override
            public void logError(Throwable error) {
                error.printStackTrace(logPw);
                logPw.flush();
            }
            @Override
            public void timeStat(GUID guid, long loadMs, long parseMs, long indexMs) {
            }
        };
        
        final Map<String, TypeMappingParser> parsers = ImmutableMap.of(
                "yaml", new YAMLTypeMappingParser());
        final TypeStorage ss = new TypeFileStorage(typesDir, mappingsDir,
                new ObjectTypeParsingRulesFileParser(), parsers, new FileLister(), logger);
        
        
        final WorkspaceClient wsClient = new WorkspaceClient(wsUrl, kbaseIndexerToken);
        wsClient.setIsInsecureHttpConnectionAllowed(true); //TODO SEC only do if http
        
        // 50k simultaneous users * 1000 group ids each seems like plenty = 50M ints in memory
        final AccessGroupProvider accessGroupProvider = new AccessGroupCache(
                new WorkspaceAccessGroupProvider(wsClient), 30, 50000 * 1000);
        
        final ElasticIndexingStorage esStorage = new ElasticIndexingStorage(esHostPort,
                FileUtil.getOrCreateSubDir(tempDir, "esbulk"));
        if (esUser != null) {
            esStorage.setEsUser(esUser);
            esStorage.setEsPassword(esPassword);
        }
        esStorage.setIndexNamePrefix(esIndexPrefix);
        
        search = new NarrativeInfoDecorator(
                new SearchMethods(accessGroupProvider, esStorage, ss, admins),
                new WorkspaceEventHandler(new CloneableWorkspaceClientImpl(wsClient)));
        //END_CONSTRUCTOR
    }

    /**
     * <p>Original spec-file function name: search_types</p>
     * <pre>
     * Search for number of objects of each type matching constraints.
     * </pre>
     * @param   params   instance of type {@link kbasesearchengine.SearchTypesInput SearchTypesInput}
     * @return   instance of type {@link kbasesearchengine.SearchTypesOutput SearchTypesOutput}
     */
    @JsonServerMethod(rpc = "KBaseSearchEngine.search_types", async=true)
    public SearchTypesOutput searchTypes(SearchTypesInput params, AuthToken authPart, RpcContext jsonRpcContext) throws Exception {
        SearchTypesOutput returnVal = null;
        //BEGIN search_types
        returnVal = search.searchTypes(params, authPart.getUserName());
        //END search_types
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: search_objects</p>
     * <pre>
     * Search for objects of particular type matching constraints.
     * </pre>
     * @param   params   instance of type {@link kbasesearchengine.SearchObjectsInput SearchObjectsInput}
     * @return   instance of type {@link kbasesearchengine.SearchObjectsOutput SearchObjectsOutput}
     */
    @JsonServerMethod(rpc = "KBaseSearchEngine.search_objects", async=true)
    public SearchObjectsOutput searchObjects(SearchObjectsInput params, AuthToken authPart, RpcContext jsonRpcContext) throws Exception {
        SearchObjectsOutput returnVal = null;
        //BEGIN search_objects
        returnVal = search.searchObjects(params, authPart.getUserName());
        //END search_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_objects</p>
     * <pre>
     * Retrieve objects by their GUIDs.
     * </pre>
     * @param   params   instance of type {@link kbasesearchengine.GetObjectsInput GetObjectsInput}
     * @return   instance of type {@link kbasesearchengine.GetObjectsOutput GetObjectsOutput}
     */
    @JsonServerMethod(rpc = "KBaseSearchEngine.get_objects", async=true)
    public GetObjectsOutput getObjects(GetObjectsInput params, AuthToken authPart, RpcContext jsonRpcContext) throws Exception {
        GetObjectsOutput returnVal = null;
        //BEGIN get_objects
        returnVal = search.getObjects(params, authPart.getUserName());
        //END get_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_types</p>
     * <pre>
     * List registered searchable object types.
     * </pre>
     * @param   params   instance of type {@link kbasesearchengine.ListTypesInput ListTypesInput}
     * @return   instance of type {@link kbasesearchengine.ListTypesOutput ListTypesOutput}
     */
    @JsonServerMethod(rpc = "KBaseSearchEngine.list_types", async=true)
    public ListTypesOutput listTypes(ListTypesInput params, RpcContext jsonRpcContext) throws Exception {
        ListTypesOutput returnVal = null;
        //BEGIN list_types
        returnVal = new ListTypesOutput().withTypes(search.listTypes(params.getTypeName()));
        //END list_types
        return returnVal;
    }
    @JsonServerMethod(rpc = "KBaseSearchEngine.status")
    public Map<String, Object> status() {
        Map<String, Object> returnVal = null;
        //BEGIN_STATUS
        returnVal = new LinkedHashMap<String, Object>();
        returnVal.put("state", "OK");
        returnVal.put("message", "");
        returnVal.put("version", SearchVersion.VERSION);
        returnVal.put("git_url", gitUrl);
        returnVal.put("git_commit_hash", gitCommitHash);
        // get eclipse to shut up about the unused constants
        @SuppressWarnings("unused")
        final String v = version;
        //END_STATUS
        return returnVal;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            new KBaseSearchEngineServer().startupServer(Integer.parseInt(args[0]));
        } else if (args.length == 3) {
            JsonServerSyslog.setStaticUseSyslog(false);
            JsonServerSyslog.setStaticMlogFile(args[1] + ".log");
            new KBaseSearchEngineServer().processRpcCall(new File(args[0]), new File(args[1]), args[2]);
        } else {
            System.out.println("Usage: <program> <server_port>");
            System.out.println("   or: <program> <context_json_file> <output_json_file> <token>");
            return;
        }
    }
}
