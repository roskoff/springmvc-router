package org.resthub.web.springmvc.router;

import jregex.Matcher;
import jregex.Pattern;
import jregex.REFlags;
import org.resthub.web.springmvc.router.exceptions.NoHandlerFoundException;
import org.resthub.web.springmvc.router.exceptions.NoRouteFoundException;
import org.resthub.web.springmvc.router.exceptions.RouteFileParsingException;
import org.resthub.web.springmvc.router.parser.ByLineRouterLoader;
import org.resthub.web.springmvc.router.parser.OpenApiRouteLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>The router matches HTTP requests to action invocations.
 * <p>Courtesy of Play! Framework Router
 *
 * @author Play! Framework developers
 * @author Brian Clozel
 * @see org.resthub.web.springmvc.router.RouterHandlerMapping
 */
public class Router {

    /**
     * Pattern used to locate a method override instruction
     */
    static Pattern methodOverride = new Pattern("^.*x-http-method-override=({method}GET|PUT|POST|DELETE|PATCH).*$");

    /**
     * Timestamp the routes file was last loaded at.
     */
    public static long lastLoading = -1;
    private static final Logger logger = LoggerFactory.getLogger(Router.class);

    public static void clear() {
        routes.clear();
    }

    /**
     * Parse the routes file. This is called at startup.
     */
    public static void load(List<Resource> fileResources) throws IOException {
        routes.clear();
        for (Resource res : fileResources) {
            routes.addAll(parse(res));
        }

        lastLoading = System.currentTimeMillis();

        logger.info("Loaded routes: \n\t{}", routes.stream().map(Route::toFixedLengthString).collect(Collectors.joining("\n\t")));
    }


    /**
     * Add a route at the given position
     */
    public static void addRoute(int position, Route route) {
        if (position > routes.size()) {
            position = routes.size();
        }
        routes.add(position, route);
    }

    /**
     * Add a route
     */
    public static void addRoute(Route route) {
        routes.add(route);
    }

    /**
     * This is used internally when reading the route file. The order the routes
     * are added matters and we want the method to append the routes to the
     * list.
     */
    public static void appendRoute(Route route) {
        routes.add(route);
    }


    /**
     * Add a new route at the beginning of the route list
     */
    public static void prependRoute(Route route) {
        routes.add(0, route);
    }

    /**
     * Parse a route file.
     *
     * @param fileResource the file to read
     * @return all found routes
     */
    static List<Route> parse(Resource fileResource) throws IOException {

        String fileAbsolutePath = fileResource.getURL().getPath();

        List<String> openApiExtensions = Arrays.asList("yml", "yaml", "json");

        for (String ext : openApiExtensions) {
            if (fileAbsolutePath.endsWith(ext)) return new OpenApiRouteLoader().load(fileResource);
        }

        return new ByLineRouterLoader().load(fileResource);
    }


    public static void detectChanges(List<Resource> fileResources) throws IOException {

        boolean hasChanged = false;

        for (Resource res : fileResources) {
            if (Files.getLastModifiedTime(res.getFile().toPath()).compareTo(FileTime.fromMillis(lastLoading)) > 0) {
                hasChanged = true;
                break;
            }
        }

        if (hasChanged) {
            load(fileResources);
        }
    }

    public static List<Route> routes = new ArrayList<>(500);

    public static Route route(HTTPRequestAdapter request) {
        if (logger.isTraceEnabled()) {
            logger.trace("Route: {} - {}", request.path, request.querystring);
        }
        // request method may be overridden if a x-http-method-override parameter is given
        if (request.querystring != null && methodOverride.matches(request.querystring)) {
            Matcher matcher = methodOverride.matcher(request.querystring);
            if (matcher.matches()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("request method {} overridden to {} ", request.method, matcher.group("method"));
                }
                request.method = matcher.group("method");
            }
        }

        for (Route route : routes) {
            MediaType format = request.format;
            String host = request.host;
            String path = request.contextPath != null ? request.path.replace(request.contextPath, "") : request.path;
            Map<String, String> args = route.matches(request.method, path, format, host);

            if (args != null) {
                request.routeArgs = args;
                request.action = route.action;
                if (args.containsKey("format")) {
                    request.setFormat(HTTPRequestAdapter.resolveFormat(args.get("format")));
                }
                if (request.action.contains("{")) {
                    for (String arg : request.routeArgs.keySet()) {
                        request.action = request.action.replace("{" + arg + "}", request.routeArgs.get(arg));
                    }
                }
                return route;
            }
        }
        // Not found - if the request was a HEAD, let's see if we can find a corresponding GET
        if (request.method.equalsIgnoreCase("head")) {
            request.method = "GET";
            Route route = route(request);
            request.method = "HEAD";
            return route;
        }
        throw new NoRouteFoundException(request.method, request.path);
    }

    public static Map<String, String> route(String method, String path) {
        return route(method, path, null, null);
    }

    public static Map<String, String> route(String method, String path, MediaType accept) {
        return route(method, path, accept, null);
    }

    public static Map<String, String> route(String method, String path, MediaType accept, String host) {
        for (Route route : routes) {
            Map<String, String> args = route.matches(method, path, accept, host);
            if (args != null) {
                args.put("action", route.action);
                return args;
            }
        }
        return new HashMap<>(16);
    }

    public static ActionDefinition reverse(String action) {
        // Note the map is not <code>Collections.EMPTY_MAP</code> because it will be copied and changed.
        return reverse(action, new HashMap<>(16));
    }

    public static String getFullUrl(String action, Map<String, Object> args) {
        return HTTPRequestAdapter.getCurrent().getBase() + reverse(action, args);
    }

    public static String getFullUrl(String action) {
        // Note the map is not <code>Collections.EMPTY_MAP</code> because it will be copied and changed.
        return getFullUrl(action, new HashMap<>(16));
    }

    public static Collection<Route> resolveActions(String action) {

        List<Route> candidateRoutes = new ArrayList<>(3);

        for (Route route : routes) {
            if (route.actionPattern != null) {
                Matcher matcher = route.actionPattern.matcher(action);
                if (matcher.matches()) {
                    candidateRoutes.add(route);
                }
            }
        }

        return candidateRoutes;
    }

    public static ActionDefinition reverse(String action, Map<String, Object> args) {

        HTTPRequestAdapter currentRequest = HTTPRequestAdapter.getCurrent();

        Map<String, Object> argsbackup = new HashMap<>(args);
        for (Route route : routes) {
            if (route.actionPattern != null) {
                Matcher matcher = route.actionPattern.matcher(action);
                if (matcher.matches()) {
                    for (String group : route.actionArgs) {
                        String v = matcher.group(group);
                        if (v == null) {
                            continue;
                        }
                        args.put(group, v.toLowerCase());
                    }
                    List<String> inPathArgs = new ArrayList<>(16);
                    boolean allRequiredArgsAreHere = true;
                    // les noms de parametres matchent ils ?
                    for (Route.Arg arg : route.args) {
                        inPathArgs.add(arg.name);
                        Object value = args.get(arg.name);
                        if (value == null) {
                            // This is a hack for reverting on hostname that are a regex expression.
                            // See [#344] for more into. This is not optimal and should retough. However,
                            // it allows us to do things like {(.*)}.domain.com
                            String host = route.host.replaceAll("\\{", "").replaceAll("\\}", "");
                            if (host.equals(arg.name) || host.matches(arg.name)) {
                                args.put(arg.name, "");
                                value = "";
                            } else {
                                allRequiredArgsAreHere = false;
                                break;
                            }
                        } else {
                            if (value instanceof List<?>) {
                                @SuppressWarnings("unchecked")
                                List<Object> l = (List<Object>) value;
                                value = l.get(0);
                            }
                            if (!value.toString().startsWith(":") && !arg.constraint.matches(value.toString())) {
                                allRequiredArgsAreHere = false;
                                break;
                            }
                        }
                    }
                    // les parametres codes en dur dans la route matchent-ils ?
                    for (String staticKey : route.staticArgs.keySet()) {
                        if (staticKey.equals("format")) {
                            if (!currentRequest.format.equals(route.staticArgs.get("format"))) {
                                allRequiredArgsAreHere = false;
                                break;
                            }
                            continue; // format is a special key
                        }
                        if (!args.containsKey(staticKey) || (args.get(staticKey) == null)
                                || !args.get(staticKey).toString().equals(route.staticArgs.get(staticKey))) {
                            allRequiredArgsAreHere = false;
                            break;
                        }
                    }
                    if (allRequiredArgsAreHere) {
                        StringBuilder queryString = new StringBuilder();
                        String path = route.path;
                        //add contextPath and servletPath if set in the current request
                        if (currentRequest != null) {

                            if (!currentRequest.servletPath.isEmpty() && !currentRequest.servletPath.equals("/")) {
                                String servletPath = currentRequest.servletPath;
                                path = (servletPath.startsWith("/") ? servletPath : "/" + servletPath) + path;
                            }
                            if (!currentRequest.contextPath.isEmpty() && !currentRequest.contextPath.equals("/")) {
                                String contextPath = currentRequest.contextPath;
                                path = (contextPath.startsWith("/") ? contextPath : "/" + contextPath) + path;
                            }
                        }
                        String host = route.host;
                        if (path.endsWith("/?")) {
                            path = path.substring(0, path.length() - 2);
                        }
                        for (Map.Entry<String, Object> entry : args.entrySet()) {
                            String key = entry.getKey();
                            Object value = entry.getValue();
                            if (inPathArgs.contains(key) && value != null) {
                                if (List.class.isAssignableFrom(value.getClass())) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> vals = (List<Object>) value;
                                    try {
                                        path = path.replaceAll("\\{(<[^>]+>)?" + key + "\\}", URLEncoder.encode(vals.get(0).toString().replace("$", "\\$"), "utf-8"));
                                    } catch (UnsupportedEncodingException e) {
                                        throw new RouteFileParsingException("RouteFile encoding exception", e);
                                    }
                                } else {
                                    try {
                                        path = path.replaceAll("\\{(<[^>]+>)?" + key + "\\}", URLEncoder.encode(value.toString().replace("$", "\\$"), "utf-8"));
                                        host = host.replaceAll("\\{(<[^>]+>)?" + key + "\\}", URLEncoder.encode(value.toString().replace("$", "\\$"), "utf-8"));
                                    } catch (UnsupportedEncodingException e) {
                                        throw new RouteFileParsingException("RouteFile encoding exception", e);
                                    }
                                }
                            } else if (route.staticArgs.containsKey(key)) {
                                // Do nothing -> The key is static
                            } else if (value != null) {
                                if (List.class.isAssignableFrom(value.getClass())) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> vals = (List<Object>) value;
                                    for (Object object : vals) {
                                        try {
                                            queryString.append(URLEncoder.encode(key, "utf-8"));
                                            queryString.append("=");
                                            if (object.toString().startsWith(":")) {
                                                queryString.append(object.toString());
                                            } else {
                                                queryString.append(URLEncoder.encode(object.toString() + "", "utf-8"));
                                            }
                                            queryString.append("&");
                                        } catch (UnsupportedEncodingException ex) {
                                        }
                                    }
//                                } else if (value.getClass().equals(Default.class)) {
//                                    // Skip defaults in queryString
                                } else {
                                    try {
                                        queryString.append(URLEncoder.encode(key, "utf-8"));
                                        queryString.append("=");
                                        if (value.toString().startsWith(":")) {
                                            queryString.append(value.toString());
                                        } else {
                                            queryString.append(URLEncoder.encode(value.toString() + "", "utf-8"));
                                        }
                                        queryString.append("&");
                                    } catch (UnsupportedEncodingException ex) {
                                    }
                                }
                            }
                        }
                        String qs = queryString.toString();
                        if (qs.endsWith("&")) {
                            qs = qs.substring(0, qs.length() - 1);
                        }
                        ActionDefinition actionDefinition = new ActionDefinition();
                        actionDefinition.url = qs.length() == 0 ? path : path + "?" + qs;
                        actionDefinition.method = route.method == null || route.method.equals("*") ? "GET" : route.method.toUpperCase();
                        actionDefinition.star = "*".equals(route.method);
                        actionDefinition.action = action;
                        actionDefinition.args = argsbackup;
                        actionDefinition.host = host;
                        return actionDefinition;
                    }
                }
            }
        }
        throw new NoHandlerFoundException(action, args);
    }

    public static class ActionDefinition {

        /**
         * The domain/host name.
         */
        public String host;
        /**
         * The HTTP method, e.g. "GET".
         */
        public String method;
        /**
         * @todo - what is this? does it include the domain?
         */
        public String url;
        /**
         * Whether the route contains an astericks *.
         */
        public boolean star;
        /**
         * @todo - what is this? does it include the class and package?
         */
        public String action;
        /**
         * @todo - are these the required args in the routing file, or the query
         * string in a request?
         */
        public Map<String, Object> args;

        public ActionDefinition add(String key, Object value) {
            args.put(key, value);
            return reverse(action, args);
        }

        public ActionDefinition remove(String key) {
            args.remove(key);
            return reverse(action, args);
        }

        public ActionDefinition addRef(String fragment) {
            url += "#" + fragment;
            return this;
        }

        @Override
        public String toString() {
            return url;
        }

        public void absolute() {
            HTTPRequestAdapter currentRequest = HTTPRequestAdapter.getCurrent();
            if (!url.startsWith("http")) {
                if (host == null || host.isEmpty()) {
                    url = currentRequest.getBase() + url;
                } else {
                    url = (currentRequest.secure ? "https://" : "http://") + host + url;
                }
            }
        }

        public ActionDefinition secure() {
            if (!url.contains("http://") && !url.contains("https://")) {
                absolute();
            }
            url = url.replace("http:", "https:");
            return this;
        }
    }

    public static class Route {

        public String getAction() {
            return action;
        }

        public String getHost() {
            return host;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public List<Arg> getArgs() {
            return args;
        }

        public Map<String, String> getStaticArgs() {
            return staticArgs;
        }


        /**
         * HTTP method, e.g. "GET".
         */
        public String method;
        public String path;
        public String action;
        Pattern actionPattern;
        List<String> actionArgs = new ArrayList<String>(3);
        Pattern pattern;
        Pattern hostPattern;
        List<Arg> args = new ArrayList<Arg>(3);
        public Map<String, String> staticArgs = new HashMap<String, String>(3);
        public List<MediaType> formats = new ArrayList<>(1);
        String host;
        Arg hostArg = null;
        public int routesFileLine;
        public String routesFile;
        static Pattern customRegexPattern = new Pattern("\\{([a-zA-Z_0-9]+)\\}");
        static Pattern argsPattern = new Pattern("\\{<([^>]+)>([a-zA-Z_0-9]+)\\}");

        public void compute() {
            this.host = "";
            this.hostPattern = new Pattern(".*");


            // URL pattern
            // Is there is a host argument, append it.
            if (!path.startsWith("/")) {
                String p = this.path;
                this.path = p.substring(p.indexOf("/"));
                this.host = p.substring(0, p.indexOf("/"));
                String pattern = host.replaceAll("\\.", "\\\\.").replaceAll("\\{.*\\}", "(.*)");

                if (logger.isTraceEnabled()) {
                    logger.trace("pattern [{}]", pattern);
                    logger.trace("host [{}]", host);
                }

                Matcher m = new Pattern(pattern).matcher(host);
                this.hostPattern = new Pattern(pattern);

                if (m.matches()) {
                    if (this.host.contains("{")) {
                        String name = m.group(1).replace("{", "").replace("}", "");
                        hostArg = new Arg();
                        hostArg.name = name;
                        if (logger.isTraceEnabled()) {
                            logger.trace("hostArg name [{}]", name);
                        }
                        // The default value contains the route version of the host ie {client}.bla.com
                        // It is temporary and it indicates it is an url route.
                        // TODO Check that default value is actually used for other cases.
                        hostArg.defaultValue = host;
                        hostArg.constraint = new Pattern(".*");

                        if (logger.isTraceEnabled()) {
                            logger.trace("adding hostArg [{}]", hostArg);
                        }

                        args.add(hostArg);
                    }
                }
            }
            String patternString = path;
            patternString = customRegexPattern.replacer("\\{<[^/]+>$1\\}").replace(patternString);
            Matcher matcher = argsPattern.matcher(patternString);
            while (matcher.find()) {
                Arg arg = new Arg();
                arg.name = matcher.group(2);
                arg.constraint = new Pattern(matcher.group(1));
                args.add(arg);
            }

            patternString = argsPattern.replacer("({$2}$1)").replace(patternString);
            this.pattern = new Pattern(patternString);
            // Action pattern
            patternString = action;
            patternString = patternString.replace(".", "[.]");
            for (Arg arg : args) {
                if (patternString.contains("{" + arg.name + "}")) {
                    patternString = patternString.replace("{" + arg.name + "}", "({" + arg.name + "}" + arg.constraint.toString() + ")");
                    actionArgs.add(arg.name);
                }
            }
            actionPattern = new Pattern(patternString, REFlags.IGNORE_CASE);
        }


        // TODO: Add args names

        private boolean contains(MediaType accept) {
            if (accept != null && !this.formats.isEmpty()) {
                for (MediaType mt: this.formats)
                    if (accept.includes(mt)) return true;
                return false;
            }
            return true;
        }

        public Map<String, String> matches(String method, String path) {
            return matches(method, path, null, null);
        }

        public Map<String, String> matches(String method, String path, MediaType accept) {
            return matches(method, path, accept, null);
        }

        /**
         * Check if the parts of a HTTP request equal this Route.
         *
         * @param method GET/POST/etc.
         * @param path   Part after domain and before query-string. Starts with a
         *               "/".
         * @param accept Format, e.g. html.
         * @param domain the domain.
         * @return ???
         */
        public Map<String, String> matches(String method, String path, MediaType accept, String domain) {
            // If method is HEAD and we have a GET
            if (method == null || this.method.equals("*") || method.equalsIgnoreCase(this.method) || (method.equalsIgnoreCase("head") && ("get").equalsIgnoreCase(this.method))) {

                Matcher matcher = pattern.matcher(path);

                boolean hostMatches = (domain == null);
                if (domain != null) {
                    Matcher hostMatcher = hostPattern.matcher(domain);
                    hostMatches = hostMatcher.matches();
                }
                // Extract the host variable
                if (matcher.matches() && contains(accept) && hostMatches) {

                    Map<String, String> localArgs = new HashMap<>();
                    for (Arg arg : args) {
                        // FIXME: Careful with the arguments that are not matching as they are part of the hostname
                        // Defaultvalue indicates it is a one of these urls. This is a trick and should be changed.
                        if (arg.defaultValue == null) {
                            localArgs.put(arg.name, matcher.group(arg.name));
                        }
                    }
                    if (hostArg != null && domain != null) {
                        // Parse the hostname and get only the part we are interested in
                        String routeValue = hostArg.defaultValue.replaceAll("\\{.*}", "");
                        domain = domain.replace(routeValue, "");
                        localArgs.put(hostArg.name, domain);
                    }
                    localArgs.putAll(staticArgs);
                    return localArgs;
                }
            }
            return null;
        }

        public static class Arg {

            String name;
            Pattern constraint;
            String defaultValue;
            Boolean optional = false;

            public String getName() {
                return name;
            }

            public String getDefaultValue() {
                return defaultValue;
            }
        }

        @Override
        public String toString() {
            return method + " " + path + " -> " + action;
        }

        public String toFixedLengthString() {
            return String.format("%-8s%-60s%-60s%-22s", method, path, action, MediaType.toString(this.formats));
        }
    }

}
