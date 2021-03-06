/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.komodo.rest.service;

import static org.komodo.rest.relational.RelationalMessages.Error.SCHEMA_SERVICE_GET_SCHEMA_ERROR;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import org.komodo.core.KEngine;
import org.komodo.core.repository.SynchronousCallback;
import org.komodo.importer.ImportMessages;
import org.komodo.importer.ImportOptions;
import org.komodo.importer.ImportOptions.ExistingNodeOptions;
import org.komodo.importer.ImportOptions.OptionKeys;
import org.komodo.relational.importer.vdb.VdbImporter;
import org.komodo.relational.profile.GitRepository;
import org.komodo.relational.profile.Profile;
import org.komodo.relational.vdb.Vdb;
import org.komodo.relational.workspace.WorkspaceManager;
import org.komodo.rest.KomodoRestException;
import org.komodo.rest.KomodoRestV1Application;
import org.komodo.rest.KomodoRestV1Application.V1Constants;
import org.komodo.rest.KomodoService;
import org.komodo.rest.relational.RelationalMessages;
import org.komodo.rest.relational.json.KomodoJsonMarshaller;
import org.komodo.rest.relational.response.KomodoStatusObject;
import org.komodo.rest.relational.response.RestGitRepository;
import org.komodo.spi.constants.StringConstants;
import org.komodo.spi.repository.KomodoObject;
import org.komodo.spi.repository.KomodoType;
import org.komodo.spi.repository.Repository;
import org.komodo.spi.repository.Repository.Id;
import org.komodo.spi.repository.Repository.UnitOfWork;
import org.komodo.spi.repository.Repository.UnitOfWork.State;
import org.komodo.utils.StringUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * A Komodo REST service for obtaining VDB information from the workspace.
 */
@Path( V1Constants.SERVICE_SEGMENT )
@Api( tags = {V1Constants.SERVICE_SEGMENT} )
public final class KomodoUtilService extends KomodoService {

    private static final String REPO_WKSP_LABEL = "Repository Workspace"; //$NON-NLS-1$

    private static final String REPO_CONFIG_LABEL = "Repository Configuration"; //$NON-NLS-1$

    private static final String REPO_VDB_TOTAL = "Repository Vdb Total"; //$NON-NLS-1$

    public static final String APP_NAME = "App Name"; //$NON-NLS-1$

    public static final String APP_TITLE = "App Title"; //$NON-NLS-1$

    public static final String APP_DESCRIPTION = "App Description"; //$NON-NLS-1$

    public static final String APP_VERSION = "App Version"; //$NON-NLS-1$

    public static final String USER_NAME = "User Name"; //$NON-NLS-1$

    public static final String WORKSPACE = "Workspace"; //$NON-NLS-1$

    /**
     * The sample vdbs provided by this service
     */
    @SuppressWarnings( "nls" )
    public static final String[] SAMPLES = {
        "parts_dynamic-vdb.xml", "portfolio-vdb.xml",
        "teiid-vdb-all-elements.xml", "tweet-example-vdb.xml",
        "northwind.xml", "financials.xml"
    };

    /**
     * @param engine
     *        the Komodo Engine (cannot be <code>null</code> and must be started)
     * @throws WebApplicationException
     *         if there is a problem obtaining the {@link WorkspaceManager workspace manager}
     */
    public KomodoUtilService(final KEngine engine) throws WebApplicationException {
        super(engine);
    }

    /**
     * @param headers
     *        the request headers (never <code>null</code>)
     * @param uriInfo
     *        the request URI information (never <code>null</code>)
     * @return about information of this service
     * @throws KomodoRestException if error occurs
     */
    @GET
    @Path(V1Constants.ABOUT)
    @ApiOperation( value = "Display status of this rest service", response = String.class )
    @ApiResponses(value = {
        @ApiResponse(code = 403, message = "An error has occurred.")
    })
    public Response about(final @Context HttpHeaders headers,
                                               final @Context UriInfo uriInfo) throws KomodoRestException {

        SecurityPrincipal principal = checkSecurityContext(headers);
        if (principal.hasErrorResponse())
            return principal.getErrorResponse();

        KomodoStatusObject repoStatus = new KomodoStatusObject();

        repoStatus.addAttribute(APP_NAME, KomodoRestV1Application.V1Constants.App.name());
        repoStatus.addAttribute(APP_TITLE, KomodoRestV1Application.V1Constants.App.title());
        repoStatus.addAttribute(APP_DESCRIPTION, KomodoRestV1Application.V1Constants.App.description());
        repoStatus.addAttribute(APP_VERSION, KomodoRestV1Application.V1Constants.App.version());

        List<MediaType> mediaTypes = headers.getAcceptableMediaTypes();
        UnitOfWork uow = null;
        try {
            Repository repo = this.kengine.getDefaultRepository();
            Id id = repo.getId();
            repoStatus.addAttribute(REPO_WKSP_LABEL, id.getWorkspaceName());
            repoStatus.addAttribute(REPO_CONFIG_LABEL, id.getConfiguration().toString());
        	
            // find VDBs
            uow = systemTx("getVdbs", true); //$NON-NLS-1$
            Vdb[] vdbs = getWorkspaceManager(uow).findVdbs(uow);
            repoStatus.addAttribute(REPO_VDB_TOTAL, Integer.toString(vdbs.length));

        } catch (final Exception e) {
            if ((uow != null) && (uow.getState() != State.ROLLED_BACK)) {
                uow.rollback();
            }

            if (e instanceof KomodoRestException) {
                throw (KomodoRestException)e;
            }

            String errorMsg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.getClass().getSimpleName();
            errorMsg = RelationalMessages.getString(RelationalMessages.Error.ABOUT_SERVICE_ERROR, errorMsg);
            repoStatus.addAttribute(REPO_VDB_TOTAL, errorMsg);
        }

        // create response
        try {
            return commit(uow, mediaTypes, repoStatus);
        } catch (Exception ex) {
            return createErrorResponseWithForbidden(mediaTypes, ex, RelationalMessages.Error.ABOUT_SERVICE_ERROR);
        }
    }

    /**
     * @param sampleName
     * @return the sample content for the given sample name
     */
    public static InputStream getVdbSample(String sampleName) {
        String sampleFilePath = "sample" + File.separator + sampleName; //$NON-NLS-1$
        InputStream fileStream = KomodoUtilService.class.getResourceAsStream(sampleFilePath);
        if (fileStream == null)
            LOGGER.error(RelationalMessages.getString(
                                                      RelationalMessages.Error.VDB_SAMPLE_CONTENT_FAILURE, sampleName));

        else
            LOGGER.info(RelationalMessages.getString(
                                                     RelationalMessages.Error.VDB_SAMPLE_CONTENT_SUCCESS, sampleName));

        return fileStream;
    }

    /**
     * Attempt to import the sample data into the engine
     *
     * @return the response indicating the sample data load has been attempted
     */
    @SuppressWarnings( "nls" )
    @POST
    @Path(V1Constants.SAMPLE_DATA)
    @Produces( MediaType.APPLICATION_JSON )
    @ApiOperation(value = "Import sample data into VdbBuilder and display the status of the operation",
                             response = KomodoStatusObject.class)
    @ApiResponses(value = {
        @ApiResponse(code = 403, message = "An error has occurred.")
    })
    public Response importSampleData(final @Context HttpHeaders headers,
                                                                       final @Context UriInfo uriInfo) {

        SecurityPrincipal principal = checkSecurityContext(headers);
        if (principal.hasErrorResponse())
            return principal.getErrorResponse();

        KomodoStatusObject status = new KomodoStatusObject("Sample Vdb Import");

        for (String sampleName : SAMPLES) {
            InputStream sampleStream = getVdbSample(sampleName);
            if (sampleStream == null) {
                status.addAttribute(sampleName, RelationalMessages.getString(
                                                          RelationalMessages.Error.VDB_SAMPLE_CONTENT_FAILURE, sampleName));
                continue;
            }

            UnitOfWork uow = null;
            try {
                SynchronousCallback callback = new SynchronousCallback();
                uow = createTransaction(principal, "Import vdb " + sampleName, false, callback); //$NON-NLS-1$

                String msg = null;

                ImportOptions importOptions = new ImportOptions();
                importOptions.setOption(OptionKeys.HANDLE_EXISTING, ExistingNodeOptions.RETURN);
                ImportMessages importMessages = new ImportMessages();
                
                Repository repo = this.kengine.getDefaultRepository();
                KomodoObject workspace = repo.komodoWorkspace(uow);
                VdbImporter importer = new VdbImporter(repo);
                importer.importVdb(uow, sampleStream, workspace, importOptions, importMessages);
                uow.commit();

                List<String> errorMsgs = importMessages.getErrorMessages();
                if (errorMsgs.isEmpty()) {
                    msg = RelationalMessages.getString(
                                                       RelationalMessages.Error.VDB_SAMPLE_IMPORT_SUCCESS,
                                                                                                              sampleName);
                } else if (errorMsgs.iterator().next().contains("node already exists")) {
                    msg = RelationalMessages.getString(
                                                       RelationalMessages.Error.VDB_SAMPLE_IMPORT_VDB_EXISTS,
                                                                                                              sampleName);
                } else {
                    String errMsg = StringUtils.toCommaSeparatedList(errorMsgs.toArray());
                    msg = RelationalMessages.getString(
                                                           RelationalMessages.Error.VDB_SAMPLE_IMPORT_ERRORS,
                                                                                                               sampleName, errMsg);
                }

                if (callback.await(3, TimeUnit.MINUTES)) {
                    status.addAttribute(sampleName, msg);
                } else {
                    status.addAttribute(sampleName, RelationalMessages.getString(
                                                                                 RelationalMessages.Error.VDB_SAMPLE_IMPORT_TIMEOUT,
                                                                                 sampleName, msg));
                }

            } catch ( final Exception e ) {
                if ( ( uow != null ) && ( uow.getState() != State.COMMITTED ) ) {
                    uow.rollback();
                }

                status.addAttribute(sampleName, RelationalMessages.getString(
                                                                             RelationalMessages.Error.VDB_SERVICE_LOAD_SAMPLE_ERROR, sampleName, e));
            }
        }

        ResponseBuilder builder = Response.ok( KomodoJsonMarshaller.marshall(status, true), MediaType.APPLICATION_JSON );
        return builder.build();
    }

    /**
     * @param headers
     *        the request headers (never <code>null</code>)
     * @param uriInfo
     *        the request URI information (never <code>null</code>)
     * @param ktype
     *        the komodo type parameter
     * @return a JSON document representing the schema of the teiid VDB (never <code>null</code>).
     *                If a ktype parameter is specified conforming to a KomodoType then only the associated
     *                element of the teiid schema is returned.
     * @throws KomodoRestException
     *         if there is a problem constructing the VDBs JSON document
     */
    @GET
    @Path(V1Constants.SCHEMA_SEGMENT)
    @Produces( MediaType.APPLICATION_JSON )
    @ApiOperation(value = "Display the schema structure of the teiid vdb",
                            response = String.class)
    @ApiResponses(value = {
        @ApiResponse(code = 404, message = "If ktype is not a recognised type"),
        @ApiResponse(code = 404, message = "If ktype is recognised but not associated with a teiid schema element"),
        @ApiResponse(code = 406, message = "Only JSON is returned by this operation"),
        @ApiResponse(code = 403, message = "An error has occurred.")
    })
    public Response getSchema( final @Context HttpHeaders headers,
                             final @Context UriInfo uriInfo,
                             @ApiParam(
                                       value = "Type of schema element to be returned",
                                       allowableValues = "Vdb, VdbImport, Connection," +
                                                                             "VdbTranslator, Model, " +
                                                                             "VdbModelSource, VdbDataRole, " +
                                                                             "VdbPermission, VdbCondition, VdbMask",
                                       required = false,
                                       allowMultiple = false
                             )
                             @QueryParam(value = "ktype") String ktype) throws KomodoRestException {

        SecurityPrincipal principal = checkSecurityContext(headers);
        if (principal.hasErrorResponse())
            return principal.getErrorResponse();

        List<MediaType> mediaTypes = headers.getAcceptableMediaTypes();
        if (! isAcceptable(mediaTypes, MediaType.APPLICATION_JSON_TYPE))
            return notAcceptableMediaTypesBuilder().build();

        try {

            String schema = null;
            if (ktype == null) {
                //
                // Request to return whole of schema
                //
                schema = KomodoJsonMarshaller.teiidElementSchema(null);
                return Response.ok().entity(schema).build();
            }

            KomodoType komodoType = KomodoType.getKomodoType(ktype);
            if (komodoType == null) {
                return createErrorResponse(Status.NOT_FOUND, mediaTypes, RelationalMessages.Error.SCHEMA_SERVICE_GET_SCHEMA_UNKNOWN_KTYPE, ktype);
            } else {
                schema = KomodoJsonMarshaller.teiidElementSchema(komodoType);
                if (EMPTY_STRING.equals(schema)) {
                    return createErrorResponse(Status.NOT_FOUND, mediaTypes, RelationalMessages.Error.SCHEMA_SERVICE_GET_SCHEMA_NOT_FOUND, ktype);
                }
            }

            return Response.ok().entity(schema).build();

        } catch ( final Exception e ) {
            if ( e instanceof KomodoRestException ) {
                throw ( KomodoRestException )e;
            }

            return createErrorResponseWithForbidden(mediaTypes, e, SCHEMA_SERVICE_GET_SCHEMA_ERROR);
        }
    }

    /**
     * @param headers
     *            the request headers (never <code>null</code>)
     * @param uriInfo
     *            the request URI information (never <code>null</code>)
     * @param validateValue
     *            the value being validated (cannot be empty)
     * @return the response (never <code>null</code>) with an entity that is
     *         either an empty string, when the name is valid, or an error
     *         message
     * @throws KomodoRestException
     *             if there is a problem validating the value or constructing
     *             the response
     */
    @GET
    @Path( V1Constants.VALIDATE_SEGMENT + StringConstants.FORWARD_SLASH + V1Constants.VALIDATE_PLACEHOLDER )
    @Produces( { MediaType.TEXT_PLAIN } )
    @ApiOperation( value = "Returns an error message if the value is invalid" )
    @ApiResponses( value = {
            @ApiResponse( code = 400, message = "The URI cannot contain encoded slashes or backslashes." ),
            @ApiResponse( code = 403, message = "An unexpected error has occurred." ),
            @ApiResponse( code = 500, message = "The value cannot be empty." )
    } )
    public Response validateValue( final @Context HttpHeaders headers,
                                     final @Context UriInfo uriInfo,
                                     @ApiParam( value = "The value being checked", required = true )
                                     final @PathParam( "validateValue" ) String validateValue ) throws KomodoRestException {

        final SecurityPrincipal principal = checkSecurityContext( headers );

        if ( principal.hasErrorResponse() ) {
            return principal.getErrorResponse();
        }

        final String errorMsg = VALIDATOR.checkValidName( validateValue );
        
        // a name validation error occurred
        if ( errorMsg != null ) {
            return Response.ok().entity( errorMsg ).build();
        }

        return Response.ok().build();
    }

    /**
     * @param headers
     *        the request headers (never <code>null</code>)
     * @param uriInfo
     *        the request URI information (never <code>null</code>)
     * @return user profile
     * @throws KomodoRestException if error occurs
     */
    @GET
    @Path(V1Constants.USER_PROFILE)
    @ApiOperation( value = "Display profile of the individual user", response = String.class )
    @ApiResponses(value = {
        @ApiResponse(code = 403, message = "An error has occurred.")
    })
    public Response userProfile(final @Context HttpHeaders headers,
                                               final @Context UriInfo uriInfo) throws KomodoRestException {

        SecurityPrincipal principal = checkSecurityContext(headers);
        if (principal.hasErrorResponse())
            return principal.getErrorResponse();

        KomodoStatusObject userProfileStatus = new KomodoStatusObject();

        userProfileStatus.addAttribute(USER_NAME, principal.getUserName());

        List<MediaType> mediaTypes = headers.getAcceptableMediaTypes();
        UnitOfWork uow = null;
        try {
            uow = createTransaction(principal, "userProfile", true ); //$NON-NLS-1$

            Repository repo = this.kengine.getDefaultRepository();
            KomodoObject workspace = repo.komodoWorkspace(uow);
            userProfileStatus.addAttribute(WORKSPACE, workspace.getAbsolutePath());

            Profile userProfile = getUserProfile(uow);
            for (GitRepository repository : userProfile.getGitRepositories(uow)) {
                RestGitRepository restRepo = new RestGitRepository(uriInfo.getBaseUri(), repository, uow);
                String jsonRepo = KomodoJsonMarshaller.marshall(restRepo);
                userProfileStatus.addAttribute("Git Repository", jsonRepo);
            }

            Vdb[] vdbs = getWorkspaceManager(uow).findVdbs(uow);
            userProfileStatus.addAttribute(REPO_VDB_TOTAL, Integer.toString(vdbs.length));
        } catch (final Exception e) {
            if ((uow != null) && (uow.getState() != State.ROLLED_BACK)) {
                uow.rollback();
            }

            if (e instanceof KomodoRestException) {
                throw (KomodoRestException)e;
            }

            String errorMsg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.getClass().getSimpleName();
            errorMsg = RelationalMessages.getString(RelationalMessages.Error.USER_PROFILE_SERVICE_ERROR, errorMsg);
            userProfileStatus.addAttribute(REPO_VDB_TOTAL, errorMsg);
        }

        // create response
        try {
            return commit(uow, mediaTypes, userProfileStatus);
        } catch (Exception ex) {
            return createErrorResponseWithForbidden(mediaTypes, ex, RelationalMessages.Error.USER_PROFILE_SERVICE_ERROR);
        }
    }

    /**
     * @param headers
     *        the request headers (never <code>null</code>)
     * @param uriInfo
     *        the request URI information (never <code>null</code>)
     * @return stashed git repository
     * @throws KomodoRestException if error occurs
     */
    @PUT
    @Path(V1Constants.USER_PROFILE + FORWARD_SLASH + V1Constants.GIT_REPOSITORY)
    @ApiOperation( value = "Store a git repository configuration in the user's profile", response = String.class )
    @ApiResponses(value = {
        @ApiResponse(code = 406, message = "Only JSON is returned by this operation"),
        @ApiResponse(code = 403, message = "An error has occurred.")
    })
    public Response stashGitRepository(final @Context HttpHeaders headers,
                                               final @Context UriInfo uriInfo,
                                               @ApiParam(
                                                         value = "" +
                                                                 "JSON of the git repository configuration:<br>" +
                                                                 OPEN_PRE_TAG +
                                                                 OPEN_BRACE + BR +
                                                                 NBSP + RestGitRepository.NAME_LABEL + ": \"Unqiue name or identifier of the git repository\"" + BR +
                                                                 NBSP + RestGitRepository.URL_LABEL + ": \"Destination git repository url\"" + COMMA + BR +
                                                                 NBSP + RestGitRepository.USER_LABEL + ": \"User name to access the git repository\"" + COMMA + BR +
                                                                 NBSP + RestGitRepository.PASSWORD_LABEL + ": \"Password to access the git repository\"" + COMMA + BR +
                                                                 NBSP + RestGitRepository.COMMIT_AUTHOR_LABEL + ": \"Name of author to use for commits\"" + COMMA + BR +
                                                                 NBSP + RestGitRepository.COMMIT_EMAIL_LABEL + ": \"Email of author to use for commits\"" + COMMA + BR +
                                                                 NBSP + RestGitRepository.BRANCH_LABEL + ": \"The git repository branch\"" + BR +
                                                                 NBSP + OPEN_PRE_CMT + "(Optional. By default: \"master\")" + CLOSE_PRE_CMT  + BR +
                                                                 CLOSE_BRACE +
                                                                 CLOSE_PRE_TAG,
                                                         required = true
                                               )
                                               final String gitRepositoryConfig) throws KomodoRestException {

        SecurityPrincipal principal = checkSecurityContext(headers);
        if (principal.hasErrorResponse())
            return principal.getErrorResponse();

        List<MediaType> mediaTypes = headers.getAcceptableMediaTypes();
        if (! isAcceptable(mediaTypes, MediaType.APPLICATION_JSON_TYPE))
            return notAcceptableMediaTypesBuilder().build();

        RestGitRepository restGitRepo = KomodoJsonMarshaller.unmarshall(gitRepositoryConfig, RestGitRepository.class);
        String repoName = restGitRepo.getName();
        String repoUrlStr = restGitRepo.getUrl();
        String repoUser = restGitRepo.getUser();
        String repoPassword = restGitRepo.getPassword();

        if (StringUtils.isBlank(repoName)) {
            return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.PROFILE_GIT_REPO_MISSING_REPO_NAME);
        }

        if (StringUtils.isBlank(repoUrlStr)) {
            return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.PROFILE_GIT_REPO_CREATE_MISSING_REPO_URL);
        }

        if (StringUtils.isBlank(repoUser)) {
            return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.PROFILE_GIT_REPO_CREATE_MISSING_REPO_USER);
        }

        if (StringUtils.isBlank(repoPassword)) {
            return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.PROFILE_GIT_REPO_CREATE_MISSING_REPO_PASSWORD);
        }

        URL repoUrl;
        try {
            repoUrl = new URL(repoUrlStr);
        } catch (MalformedURLException ex) {
            return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.PROFILE_GIT_REPO_CREATE_MALFORMED_URL);
        }

        UnitOfWork uow = null;
        try {
            uow = createTransaction(principal, "addUserProfileGitRepository", false); //$NON-NLS-1$

            Profile userProfile = getUserProfile(uow);

            //
            // Secure the password using encryption
            //
            String encrypted = encryptSensitiveData(headers, principal.getUserName(), repoPassword);
            if (encrypted == null)
                return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.ENCRYPT_FAILURE, principal.getUserName());

            encrypted = ENCRYPTED_PREFIX + encrypted;
            GitRepository gitRepository = userProfile.addGitRepository(uow, repoName, repoUrl,
                                                                                                                                     repoUser, encrypted);
            String branch = restGitRepo.getBranch();
            if (branch != null)
                gitRepository.setBranch(uow, branch);

            String commitAuthor = restGitRepo.getCommitAuthor();
            if (commitAuthor != null)
                gitRepository.setCommitAuthor(uow, commitAuthor);

            String commitEmail = restGitRepo.getCommitEmail();
            if (commitEmail != null)
                gitRepository.setCommitEmail(uow, commitEmail);

            final RestGitRepository entity = new RestGitRepository(uriInfo.getBaseUri(), gitRepository, uow);
            return commit(uow, mediaTypes, entity);

        } catch (final Exception e) {
            if ((uow != null) && (uow.getState() != State.ROLLED_BACK)) {
                uow.rollback();
            }

            if (e instanceof KomodoRestException) {
                throw (KomodoRestException)e;
            }

            return createErrorResponseWithForbidden(mediaTypes, e, RelationalMessages.Error.PROFILE_GIT_REPO_CREATE_ERROR);
        }
    }

    /**
     * @param headers
     *        the request headers (never <code>null</code>)
     * @param uriInfo
     *        the request URI information (never <code>null</code>)
     * @return a JSON document representing the results of the removal
     * @throws KomodoRestException if error occurs
     */
    @DELETE
    @Path(V1Constants.USER_PROFILE + FORWARD_SLASH +
                  V1Constants.GIT_REPOSITORY + FORWARD_SLASH +
                  V1Constants.GIT_REPO_PLACEHOLDER)
    @ApiOperation( value = "Remove a git repository configuration from the user's profile", response = String.class )
    @ApiResponses(value = {
        @ApiResponse(code = 406, message = "Only JSON is returned by this operation"),
        @ApiResponse(code = 403, message = "An error has occurred.")
    })
    public Response removeGitRepository(final @Context HttpHeaders headers,
                                               final @Context UriInfo uriInfo,
                                               @ApiParam(
                                                         value = "Name of the git repository to remove",
                                                         required = true
                                               )
                                               final @PathParam("gitRepositoryName") String gitRepositoryName) throws KomodoRestException {

        SecurityPrincipal principal = checkSecurityContext(headers);
        if (principal.hasErrorResponse())
            return principal.getErrorResponse();

        List<MediaType> mediaTypes = headers.getAcceptableMediaTypes();
        if (! isAcceptable(mediaTypes, MediaType.APPLICATION_JSON_TYPE))
            return notAcceptableMediaTypesBuilder().build();

        if (StringUtils.isBlank(gitRepositoryName)) {
            return createErrorResponseWithForbidden(mediaTypes, RelationalMessages.Error.PROFILE_GIT_REPO_MISSING_REPO_NAME);
        }

        UnitOfWork uow = null;
        try {
            uow = createTransaction(principal, "removeUserProfileGitRepository", false); //$NON-NLS-1$

            Profile userProfile = getUserProfile(uow);
            GitRepository[] repositories = userProfile.getGitRepositories(uow, gitRepositoryName);
            if (repositories.length == 0)
                return Response.noContent().build();

            userProfile.removeGitRepository(uow, gitRepositoryName);

            KomodoStatusObject kso = new KomodoStatusObject("Delete Status"); //$NON-NLS-1$
            kso.addAttribute(gitRepositoryName, "Successfully deleted"); //$NON-NLS-1$

            return commit(uow, mediaTypes, kso);

        } catch (final Exception e) {
            if ((uow != null) && (uow.getState() != State.ROLLED_BACK)) {
                uow.rollback();
            }

            if (e instanceof KomodoRestException) {
                throw (KomodoRestException)e;
            }

            return createErrorResponseWithForbidden(mediaTypes, e, RelationalMessages.Error.PROFILE_GIT_REPO_REMOVE_ERROR);
        }
    }
}
