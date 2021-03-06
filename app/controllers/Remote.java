package controllers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import models.Bundle;
import models.CmdArgs;
import models.Field;
import models.OutItem;
import models.OutResult;
import models.Repo;
import models.Service;
import models.Status;

import org.apache.commons.io.FileUtils;

import play.Logger;
import play.data.validation.Error;
import play.data.validation.Validation;
import play.libs.IO;
import play.mvc.Before;
import play.mvc.Finally;
import util.Utils;
import bundle.BundleRegistry;

/**
 * Remoting controller to male the services accessible via API 
 * 
 * @author Paolo Di Tommaso
 *
 */
public class Remote extends CommonController {
	
	static private ThreadLocal<Bundle> bundle = new ThreadLocal<Bundle>();
	
	@Before
	static void before(String bundle) { 
		/* 
		 * preliminary checks
		 */
		assertNotEmpty(bundle,"Missing 'bundle' parameter");

		Bundle _bundle = BundleRegistry.instance().get(bundle);
		assertNotNull(_bundle, "Missing bundle for name: '%s'", bundle);
		
		/* 
		 * 1) save the bundle instance in the current context 
		 * 2) save as route argument
		 * 3) inject implict variables 
		 */
		Remote.bundle.set(_bundle);
		routeArgs.put("bundle", bundle);

		request.format = "xml";
		response.contentType = "text/xml";

	}
	
	@Finally
	static void release() {
		Service.release();
		Remote.bundle.remove();
	}	

	/**
	 * Return the list of available services
	 */
	public static void services() { 
		List<Service> services = bundle.get().services;
		render("Remote/services.xml", services);
	}

	/** 
	 * Submit a request for execution 
	 * 
	 * @param name the service name to be invoked 
	 */
	public static void submit (String name) { 

		Service service = bundle.get().getService(name);
		if( service == null ) { 
			error(String.format("Unknown service '%s' for bundle '%s", name, bundle.get().name));
		}
		service = service.copy();
		Service.current(service);

		if( !service.validate(params) ) {
			/* if the validation FAIL go back to the service page */
			renderArgs.put("service", service);
			
			StringBuilder result = new StringBuilder("The following fields do not pass validation:\n");
			for( Error err : Validation.errors() ) { 
				result.append( "- " ).append( err.getKey() ) .append( ": " ) .append( err.message() ) .append( "\n");
			}

			error(result.toString());
			return;
		} 
		
		/*
		 * 1. prepare for the execution
		 */
		boolean cache = true;
		service.init(cache);
		
		
		/*
		 * 2. check if this request has already been processed in some way 
		 */
		Status status = service.repo().getStatus();
		if( status.isReady() ) {
			service.start();
			status = Status.RUNNING;
		}

		/*
		 * render the response XML
		 */
		renderArgs.put("status", status.toString());
		renderArgs.put("rid", service.rid());
		renderArgs.put("url", service.getResultURL());
		render("Remote/submit.xml");
	}
	
	/**
	 * Render the 'result' object for request identified by 'rid'
	 * 
	 * @param rid the request unique identifier 
	 */
	public static void result( String rid ) { 

	   	Repo ctx = new Repo(rid,false);
	   	String status = ctx.getStatus().toString();
	   	OutResult result = ctx.getResult();
	   	long elapsedTime = result != null 
	   					? result.elapsedTime
	   					: System.currentTimeMillis() - ctx.getCreationTime();
		
	   	/* read the command line file */
	   	String cmdLine=null;
	   	OutItem cmdItem; 
	   	if( result != null && (cmdItem=result.getCommandLine())!= null && cmdItem.exists()) { 
	   		cmdLine = IO.readContentAsString(cmdItem.file); 
	   		if( cmdLine != null ) cmdLine = cmdLine.trim();
	   	}
	   	
	   	// if the file exists load the result object and show it
		renderArgs.put("ctx", ctx);
		renderArgs.put("status", status);
		renderArgs.put("result", result );
		renderArgs.put("elapsedTime", elapsedTime);
		renderArgs.put("cmdline", cmdLine);
		response.contentType = "text/xml";
		render("Remote/result.xml");
		
	}
	
	
	public static void run() throws IOException { 

		String args = null;
		List<File> files = new ArrayList<File>();
		List<String> paramNames = new ArrayList(params.all().keySet());
		if( paramNames != null ) for( String name : paramNames ) {
			if( "args".equals(name) ) { 
				args = params.get(name);
			}
			else if ( name.startsWith("file:") ) { 
				File file = params.get(name, File.class);
				files.add(file);
			}
		}
		
		if( Utils.isEmpty(args) ) { 
			badreq("Missing 'args' parameter");
		}
	
		/*
		 * 1. create the tcoffee command 
		 */
		
		CmdArgs cmdline = new CmdArgs(args);
		
		/* 
		 * constraint on other_pg argument 
		 */
		List<String> valid = Arrays.asList(new String[] { "aln_compare", "seq_reformat", "trmsd", "extract_from_pdb" }); 
		String other_pg = cmdline.get("other_pg");
		if( Utils.isNotEmpty(other_pg) && !valid.contains(other_pg)) { 
			badreq("Argument '-other_pg=%s' is not supported by the server", other_pg);
		}

		/*
		 * Look we assume that the bundle must define a service named 'advanced'
		 * having at least one field named 'cmdline'
		 */
		Service service = service(bundle.get().name, "adv-cmdline").copy();  
		// special tag to indentify invocation from the remote client 
		service.source = "cloud-coffee";
		// get the 'cmdline' field and pass the value
		Field field = service.input.getField("cmdline");
		if( field == null ) { 
			badreq("Malformed remoting service. It must define a field named 'cmdline'");
		}
		
		field.value = args; 
		Service.current(service);
		
		
		boolean cache = true;
		service.init(cache);
		
		/* 
		 * 2. copy downloaded files 
		 */
		if( files != null ) for( File file : files ) { 
			Logger.debug("Copying downloaded file: %s to repo: %s", file, service.repo().getFile());
			FileUtils.copyFile(file, new File(service.repo().getFile(), file.getName()));
		}
		
		
		/*
		 * 3. check if this request has already been processed in some way 
		 */
		Status status = service.repo().getStatus();
		if( status.isReady() ) {
			service.start();
			status = Status.RUNNING;
		}

		/*
		 * render the response XML
		 */
		renderArgs.put("status", status.toString());
		renderArgs.put("rid", service.rid());
		renderArgs.put("url", service.getResultURL());
		
		render("Remote/submit.xml");		
	}
	
	public static void ping() { 
		render("Remote/ping.xml");
	}
	
	
 }
