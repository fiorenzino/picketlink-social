/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.picketlink.social.openid.auth;

import java.io.IOException;
import java.net.URL;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.log4j.Logger;
import org.openid4java.consumer.ConsumerException;
import org.openid4java.consumer.ConsumerManager;
import org.openid4java.consumer.VerificationResult;
import org.openid4java.discovery.DiscoveryException;
import org.openid4java.discovery.DiscoveryInformation;
import org.openid4java.discovery.Identifier;
import org.openid4java.message.AuthRequest;
import org.openid4java.message.AuthSuccess;
import org.openid4java.message.MessageException;
import org.openid4java.message.ParameterList;
import org.openid4java.message.ax.AxMessage;
import org.openid4java.message.ax.FetchRequest;
import org.openid4java.message.ax.FetchResponse;
import org.picketlink.identity.federation.core.util.StringUtil;
import org.picketlink.social.openid.OpenIdPrincipal;
import org.picketlink.social.openid.constants.OpenIDAliasMapper;

/**
 * Processor for the OpenID interaction
 * @author Anil Saldhana
 * @since Sep 22, 2011
 */
public class OpenIDProcessor
{
   protected static Logger log = Logger.getLogger(OpenIDProcessor.class);
   protected boolean trace = log.isTraceEnabled();
   
   public static final String AUTH_TYPE = "authType";
   
   private ConsumerManager openIdConsumerManager;
   private FetchRequest fetchRequest;
   
   private String openIdServiceUrl = null;
   
   private String returnURL = null;
   
   private String requiredAttributes,optionalAttributes = null;
   
   private boolean initialized = false;

   protected List<String> roles = new ArrayList<String>();
   
   public static ThreadLocal<Principal> cachedPrincipal = new ThreadLocal<Principal>();
   
   public static ThreadLocal<List<String>> cachedRoles = new ThreadLocal<List<String>>();
   public static String EMPTY_PASSWORD = "EMPTY";

   private enum STATES { AUTH, AUTHZ, FINISH};
   
   private enum Providers
   {
      GOOGLE("https://www.google.com/accounts/o8/id"),
      YAHOO("https://me.yahoo.com/"),
      MYSPACE("myspace.com"),
      MYOPENID("https://myopenid.com/");
      
      private String name;

      Providers(String name)
      {
         this.name = name;
      }
      String get()
      {
         return name;
      }
   }
   
   public OpenIDProcessor(String theReturnURL, String requiredAttributes, String optionalAttributes)
   {
      this.returnURL = theReturnURL;
      this.requiredAttributes = requiredAttributes;
      this.optionalAttributes = optionalAttributes;
   }
   
   /**
    * Return whether the processor has initialized
    * @return
    */
   public boolean isInitialized()
   {
      return initialized;
   }
   
   /**
    * Initialize the processor
    * @param requiredRoles
    * @throws MessageException
    * @throws ConsumerException
    */
   public void initialize(List<String> requiredRoles) throws MessageException, ConsumerException
   {
      if(openIdConsumerManager == null)
         openIdConsumerManager = new ConsumerManager();
      
      fetchRequest = FetchRequest.createFetchRequest();
      //Work on the required attributes
      if(StringUtil.isNotNull(requiredAttributes))
      {
         List<String> tokens = StringUtil.tokenize(requiredAttributes);
         for(String token: tokens)
         {
            fetchRequest.addAttribute(token, OpenIDAliasMapper.get(token),true);
         }
      }
      //Work on the optional attributes
      if(StringUtil.isNotNull(optionalAttributes))
      {
         List<String> tokens = StringUtil.tokenize(optionalAttributes);
         for(String token: tokens)
         {
            String type = OpenIDAliasMapper.get(token);
            if(type == null)
            {
               log.error("Null Type returned for " + token);
            }
            fetchRequest.addAttribute(token, type,false);
         }
      }
      
      roles.addAll(requiredRoles);
      initialized = true;
   }
   
   @SuppressWarnings("unchecked")
   public boolean prepareAndSendAuthRequest(Request request, Response response) throws IOException
   { 
      //Figure out the service url
      String authType = request.getParameter(AUTH_TYPE);
      if(authType == null || authType.length() == 0)
      {
         authType = (String) request.getSession().getAttribute(AUTH_TYPE);
      }
      determineServiceUrl(authType);
      
      String openId = openIdServiceUrl;
      Session session = request.getSessionInternal(true);
      if(openId != null)
      { 
         session.setNote("openid", openId);
         List<DiscoveryInformation> discoveries;
         try
         {
            discoveries = openIdConsumerManager.discover(openId);
         }
         catch (DiscoveryException e)
         { 
            throw new RuntimeException(e);
         }

         DiscoveryInformation discovered = openIdConsumerManager.associate(discoveries);
         session.setNote("discovery", discovered);
         try
         {
            AuthRequest authReq = openIdConsumerManager.authenticate(discovered, returnURL);

            //Add in required attributes
            authReq.addExtension(fetchRequest);
            
            String url = authReq.getDestinationUrl(true);
            response.sendRedirect(url);
            
            request.getSession().setAttribute("STATE", STATES.AUTH.name());
            return false;
         }
         catch (Exception e)
         { 
            throw new RuntimeException(e);
         }
      } 
      return false;
   }
   
   @SuppressWarnings("unchecked")
   public Principal processIncomingAuthResult(Request request, Response response, Realm realm) throws IOException
   {
      Principal principal = null;
      Session session = request.getSessionInternal(false);
      if(session == null)
         throw new RuntimeException("wrong lifecycle: session was null");
      
      // extract the parameters from the authentication response
      // (which comes in as a HTTP request from the OpenID provider)
      ParameterList responseParamList = new ParameterList(request.getParameterMap());
      // retrieve the previously stored discovery information
      DiscoveryInformation discovered = (DiscoveryInformation) session.getNote("discovery");
      if(discovered == null)
         throw new RuntimeException("discovered information was null");
      // extract the receiving URL from the HTTP request
      StringBuffer receivingURL = request.getRequestURL();
      String queryString = request.getQueryString();
      if (queryString != null && queryString.length() > 0)
         receivingURL.append("?").append(request.getQueryString());

      // verify the response; ConsumerManager needs to be the same
      // (static) instance used to place the authentication request
      VerificationResult verification;
      try
      {
         verification = openIdConsumerManager.verify(receivingURL.toString(), responseParamList, discovered);
      }
      catch (Exception e)
      { 
         throw new RuntimeException(e);
      }

      // examine the verification result and extract the verified identifier
      Identifier identifier = verification.getVerifiedId();

      if (identifier != null)
      {
         AuthSuccess authSuccess = (AuthSuccess) verification.getAuthResponse();

         Map<String, List<String>> attributes = null;
         if (authSuccess.hasExtension(AxMessage.OPENID_NS_AX))
         {
            FetchResponse fetchResp;
            try
            {
               fetchResp = (FetchResponse) authSuccess.getExtension(AxMessage.OPENID_NS_AX);
            }
            catch (MessageException e)
            {
               throw new RuntimeException(e);
            }

            attributes = fetchResp.getAttributes();
         }

         OpenIdPrincipal openIDPrincipal = createPrincipal(identifier.getIdentifier(), discovered.getOPEndpoint(),
               attributes);
         request.getSession().setAttribute("PRINCIPAL", openIDPrincipal);
         
         String principalName = openIDPrincipal.getName();
         cachedPrincipal.set(openIDPrincipal);
         
         if(isJBossEnv())
         {
            cachedRoles.set(roles);
            principal = realm.authenticate(principalName, EMPTY_PASSWORD); 
         }
         else
         { 
            //Create a Tomcat Generic Principal
            principal = new GenericPrincipal(realm, principalName, null, roles, openIDPrincipal);
         }

         if(trace)
            log.trace("Logged in as:" + principal); 
      }
      else
      {
         response.sendError(HttpServletResponse.SC_FORBIDDEN);
      }
      return principal;
   }

   private OpenIdPrincipal createPrincipal(String identifier, URL openIdProvider, Map<String, List<String>> attributes)
   {
      return new OpenIdPrincipal(identifier, openIdProvider, attributes);
   }
   
   private boolean isJBossEnv()
   {
      ClassLoader tcl = SecurityActions.getContextClassLoader();
      Class<?> clazz = null;
      try
      {
         clazz = tcl.loadClass("org.jboss.system.Service");
      }
      catch (ClassNotFoundException e)
      { 
      }
      if( clazz != null )
         return true;
      return false;
   }
   
   private void determineServiceUrl(String service)
   {
      openIdServiceUrl = Providers.GOOGLE.get();
      if(StringUtil.isNotNull(service))
      {
         if("google".equals(service))
            openIdServiceUrl = Providers.GOOGLE.get();
         else if("yahoo".equals(service))
            openIdServiceUrl = Providers.YAHOO.get();
         else if("myspace".equals(service))
            openIdServiceUrl = Providers.MYSPACE.get();
         else if("myopenid".equals(service))
            openIdServiceUrl = Providers.MYOPENID.get();
      }
   }
}