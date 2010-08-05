package controllers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import models.AppConf;
import models.AppProps;
import models.History;
import models.Module;
import models.OutItem;
import models.OutResult;
import models.Repo;
import models.Status;

import org.apache.commons.io.FileUtils;

import play.Logger;
import play.libs.IO;
import play.mvc.After;
import play.mvc.Before;
import play.templates.JavaExtensions;
import util.Utils;
import util.XStreamHelper;
import edu.emory.mathcs.backport.java.util.Arrays;
import exception.QuickException;

/**
 * The main application controller 
 * 
 * @author Paolo Di Tommaso
 *
 */

public class Application extends BaseController {
	
	static List<String> PAGES = Arrays.asList(new String[]{"index", "history", "references", "help", "contacts" });
	
	
	@Before
	static void init() {
		String a = request.actionMethod;
		if( PAGES.contains(a)) {
			session.put("menu", a);
		}
		else {
			a = session.get("menu");
			if( !PAGES.contains(a) ) {
				session.put("menu", PAGES.get(0));
			}
		}
		
	}
	
	@After 
	static void release() {
		Module.release();
	}
	
	/** 
	 * Renders the main application index page
	 */
    public static void index() {
    	AppConf conf = AppConf.instance();
        render(conf);
    }
    
    /**
     * Renders the <i>References</i> html page
     */
    public static void references() {
    	render();
    }
    
    /**
     * Renders the <i>Help</i> html page
     */
    public static void help() {
    	render();
    }
    
    /**
     * Renders the <i>Contacts</i> html page
     */
    public static void contacts() {
    	render();
    }
    

    /**
     * Handles the Welcome page showed at application first run 
     */
    public static void welcome() {
    	render();
    }
    
    

    /*
     * fake page used for tests purpose only   
     */
    public static void sandbox() {
    	render();
    }
    
    
 
    /**
     * Handle request to display the <i>result</i> page
     * 
     * @param rid the unique request identifier 
     */
    public static void result(String rid, Boolean ... cached ) {		
    	
    	final Repo ctx = new Repo(rid,false);
    	final Status status = ctx.getStatus();

    	if( status.isDone()) {
			// if the file exists load the result object and show it
			OutResult result = ctx.getResult();
    		render("Application/result.html", rid, ctx, result, cached);		
		}
		else if( status.isFailed() ) {
			OutResult result = ctx.getResult();
	    	render("Application/failed.html", rid, ctx, result, cached);
		}
		else if( status.isRunning() ) {
			render("Application/wait.html", rid );
		}
		else {
			int maxDays = AppProps.instance().getRequestTimeToLive() / 60 / 60 / 24;
	    	render("Application/oops.html", rid, maxDays);
		}
 
   }

	/**
	 * Renders the history page 
	 */
	public static void history() { 
		render();
	}
	
	/**
	 * Renders the history html table  
	 */
	public static void historyTable() {
		List<History> recent = History.findAll();
		Collections.sort(recent, History.DescBeginTimeSort.INSTANCE);
		render(recent);
	}

	
	/**
	 * Check the current status for the alignment request specified by <code>rid</code>
	 * 
	 * @param rid the request unique identifier
	 */
	public static void status(String rid) {
		Repo ctx = new Repo(rid,false);
		renderText(ctx.getStatus().toString());
	}
	
	public static void replay( String rid ) {
		/* 
		 * 1. check if a result exists 
		 */
		Repo repo = new Repo(rid);
		if( !repo.hasResult() ) {
			notFound(String.format("The specified request ID does not exist (%s)", rid));
		}
		
		/* 
		 * create the module and bind the stored values 
		 */
		String mode = repo.getResult().mode;
		Module module = AppConf.instance().module(mode).copy();
		Module.current(module);
		module.input = XStreamHelper.fromXML(repo.getInputFile());
		
		/* 
		 * 3. show the input form ('module.html')
		 */
		renderArgs.put("module", module);
		render("Application/module.html");		
	}
	
	public static void submit( String rid ) {
		/*
		 * 1. check and load the repo context object 
		 */
		Repo repo = new Repo(rid);
		if( !repo.hasResult() ) {
			notFound(String.format("The specified request ID does not exist (%s)", rid));
		}

		/* 
		 * 2. create and bind the stored input values 
		 */
		OutResult result = repo.getResult(); 
		Module module = AppConf.instance().module(result.mode).copy();
		Module.current(module);
		module.input = XStreamHelper.fromXML(repo.getInputFile());
		
		/* 
		 * 4. re-execute with caching feature disabled
		 */
		exec(module,false);
	}
	
	/**
	 * Renders a generic t-coffee 'module' i.e. a specific configuration defined in the main application file 
	 * 
	 * @param name the <i>module</i> name i.e. is unique identifier
	 */
	public static void module(String name) {
		
		if( isGET() ) {
			Logger.debug("Rendering module w/t name: %s", name);
			Module module = AppConf.instance().module(name);
			render(module);
			return;
		}

		/*
		 * process the submitted data
		 */
		String mid = params.get("_mid");
		Module module = AppConf.instance().module(mid).copy();
		Module.current(module);

		if( !module.validate(params) ) {
			/* if the validation FAIL go back to the module page */
			renderArgs.put("module", module);
			render();
			return;
		} 

		exec(module,true);
	}
	
	static void exec( Module module, boolean enableCaching ) {
		
		/*
		 * 1. prepare for the execution
		 */
		module.init(enableCaching);
		
		
		/*
		 * 2. check if this request has already been processed in some way 
		 */
		Status status = module.repo().getStatus();
		if( !status.isReady() ) {
	    	Logger.debug("Current request status: '%s'. Forward to result page with rid: %s", status, module.rid());
	    	result(module.rid(), module.repo().cached);
	    	return;
		}

		/*
		 * 3. fire the job 
		 */
		if( module.start() ) {
	    	
			/*
	    	 * 4. store the current request-id in a cookie
	    	 */
	    	History history = new History(module.rid());
	    	history.setMode(module.title);
	    	history.save();		
		}
		

    	/*
    	 * 5. forwards to the result page 
    	 */
    	Logger.debug("Forward to result page with rid: %s", module.rid());
    	result(module.rid());			
	}

	/**
	 * Create a temporary zip file with all generated content and download it
	 * 
	 * @param rid the request identifier
	 * @throws IOException 
	 */
	public static void zip(String rid) throws IOException {
		Repo repo = new Repo(rid);
		if( !repo.hasResult() ) {
			notFound(String.format("The requested download is not available (%s) ", rid));
			return;
		}
		
		OutResult result = repo.getResult();
		File zip = File.createTempFile("download", ".zip", repo.getFile());
		zipThemAll(result.getItems(), zip);
		renderBinary(zip, String.format("tcoffee-all-files-%s.zip",rid));
	}
	
	static void zipThemAll( List<OutItem> items, File file ) {

		try {
			ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file));
			
			for( OutItem item : items ) { 
				if( item.file==null || !item.file.exists() ) { continue; }
				
				// add a new zip entry
				zip.putNextEntry( new ZipEntry(item.file.getName()) );
				
				// append the file content
				FileInputStream in = new FileInputStream(item.file);
				IO.write(in, zip);
	 
				// Complete the entry 
				zip.closeEntry(); 
				in.close(); 		
			}
			
			zip.close();					
		}
		catch (IOException e) {
			throw new QuickException(e, "Unable to zip content to file: '%s'", file);
		}
	} 
	
	/**
	 * Manage user input file uploads
	 * 
	 * @param name the file name that is being uploaded
	 */
	public static void upload(String name) {
		/* default error result */
		String ERROR = "{success:false}";
		
		/* 
		 * here it is the uploaded file 
		 */
		File file = params.get(name, File.class);
		
		/* uh oh something goes wrong .. */
		if( file==null ) {
			Logger.error("Ajax upload is null for field: '%s'", name);
			renderText(ERROR);
			return;
		}
		
		/* error condition: wtf is that file ? */
		if( !file.exists() ) {
			Logger.error("Cannot find file for ajax upload field: '%s'", name);
			renderText(ERROR);
			return;
		}

		/* 
		 * copy the uploaded content to a temporary file 
		 * and return that name in the result to be stored in a hidden field
		 */
		try {
			File temp = File.createTempFile("upload-", null);
			// to create a temporary folder instead of a file delete and recreate it 
			temp.delete();
			temp.mkdir();
			temp = new File(temp, file.getName());
			
			FileUtils.copyFile(file, temp);
			String filename = Utils.getCanonicalPath(temp);
			renderText(String.format("{success:true, name:'%s', path:'%s', size:'%s'}", 
						file.getName(),
						JavaExtensions.escapeJavaScript(filename),
						FileUtils.byteCountToDisplaySize(temp.length())
						));
		}
		catch( IOException e ) {
			Logger.error(e, "Unable to copy temporary upload file: '%s'", file);
			renderText(ERROR);
		}
		
	}
	
	
	

}