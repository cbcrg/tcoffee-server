package controllers;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import models.AppProps;
import models.Bundle;
import models.Field;
import models.PageContent;
import models.Repo;
import models.Service;
import models.TCoffeeCommand;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.dom4j.Attribute;
import org.dom4j.Element;

import play.Logger;
import play.Play;
import play.cache.Cache;
import play.libs.IO;
import play.mvc.Before;
import play.mvc.Scope.Session;
import play.mvc.With;
import play.mvc.results.Result;
import play.templates.JavaExtensions;
import play.vfs.VirtualFile;
import util.Check;
import util.FileIterator;
import util.ReloadableSingletonFile;
import util.Utils;
import util.XStreamHelper;
import bundle.BundleException;
import bundle.BundleHelper;
import bundle.BundleRegistry;
import edu.emory.mathcs.backport.java.util.Collections;
import exception.QuickException;

/**
 * The administration panel controller 
 * 
 * See {@link Security}
 * 
 * @author Paolo Di Tommaso
 *
 */
@With(Auth.class)
public class Admin extends CommonController {

	
	public final static File USERS_FILE;
	
	public final static ReloadableSingletonFile<List<String>> USERS_LIST;
	
	static { 
		/*
		 * defines the users list file
		 */
		 USERS_FILE = new File( AppProps.WORKSPACE_FOLDER, "users.properties");
		 Logger.info("Users list file: '%s'", USERS_FILE);
		 
		 
		 /*
		  * defines the 
		  */
		 USERS_LIST = new ReloadableSingletonFile<List<String>>(USERS_FILE) {
			 
			 public List<String> readFile(File file) {
				 List<String> result = new ArrayList<String>();
				 
				 if( file != null && file.exists() ) { 
					 for( String line : new FileIterator(file) ) { 
						 result.add(line);
					 }
				 }
				 
				 /* sort them */
				 Collections.sort(result);
				 
				 return result;
			 };
			 
		 };
		 
	}
	
	
	@Before
	static void before() { 
		injectImplicitVars();
		/* clear alwasy sys message for admin pages */
		renderArgs.put("_main_sysmsg", null);
	}
	
	/*
	 * load a service template configuration file 
	 *  
	 * @param path
	 * @return
	 */
	@Deprecated
	static Service loadModuleFile( String path  ) {
		for( VirtualFile templatePath : Play.templatesPath ) {
			if( templatePath == null ) continue;
			
			VirtualFile file = templatePath.child(path);
			if( file.exists() ) {
				return XStreamHelper.fromXML( file.getRealFile() );
			}
		}
		
		throw new QuickException("Specified template file definition does not exists: %s", path);
	}	
	
	
	
	/**
	 * Show the administration index page
	 */
	public static void index() {
		render();
	}
	
	/**
	 * Implements the clear cache function. Invoking it with GET method show a confirmation page, 
	 * when it is invoked with POST method (pressing the confirmaton button) will delete all the 
	 * cached directories
	 * 
	 * see {@link Repo#deleteAll()}
	 */
    public static void clearCache() {
    	if( isGET() ) {
    		/* ask confirmation */
    		render();
    	}
    	else if( isPOST()) {
    		/* DO IT - delete any job repo in any status */
    		Repo.deleteAll();
    		index();
    	}
    	
    }
 
    /**
     * Let to inspect and modify application properties 
     * 
     */
    public static void editprops() {
    	final String cachekey = session.getId() + "_appinfo";

    	AppProps props = null;
    	if( isGET() ) { 
    		/* create a copy to work on and save in the cache  */
    		props = new AppProps(AppProps.instance());
    		Cache.set(cachekey, props);
		
    	}
    	else if ( isPOST () ) { 
    		/* save to file */
    		props = (AppProps) Cache.get(cachekey);
    		props.save();
    		
    		/* add the 'saved' flag to notify the save action */
    		renderArgs.put("saved", true);
    		
    	}

    	if( props == null ) { 
    		error("Missing application properties");
    	}
    	
    	List<String> names = props.getNames();
    	Collections.sort(names);
	
		/* just render the form */
		render(props,names);
    }
    
    /**
     * This method is invoked when a property value is update using by an ajax request.
     * <p>
     * Please NOTE: changing parameters name will break the code (they must match the javascript plugin definition)
     * </p>
     * 
     * 
     * @param element_id the property key hash code
     * @param original_html the previous property value 
     * @param update_value the new property value
     */
    public static void updateProperty( String element_id, String original_html, String update_value) { 
    	
    	if( Utils.isEmpty(element_id) ) { 
    		error("Empty element_id");
    	}
    	
    	
    	final String cachekey = session.getId() + "_appinfo";
    	AppProps props = (AppProps) Cache.get(cachekey);

    	/*
    	 * special action to add a property 
    	 */
    	if( "__add" .equals(element_id) ) { 
    		if( Utils.isEmpty(update_value) ) { 
    			error("Property have to be entered in the format 'name=value'");
    		}
    		
    		String[] pair = update_value.split("=");
    		if( pair==null || pair.length!=2 || Utils.isEmpty(pair[0].trim()) || Utils.isEmpty(pair[1].trim()) ) { 
    			error("Property must be in the format 'name=value'");
    		}
    		
    		// set the property
    		props.setProperty(pair[0].trim(), pair[1].trim());
    		Logger.info("Adding application property: %s", update_value);
    		
    		// this is requied to confirm the action
    		renderText(update_value);
    	}

    	/*
    	 * special element_id to delete a property 
    	 */
    	if( "__del" .equals(element_id) ) { 
    		Logger.info("Deleting application property: %s", original_html);
    		props.remove(original_html);

    		// this is requied to confirm the action
    		renderText(update_value);
    	}
    	
    	/* 
    	 * note: the element_id encode the property name hash code, so we need to map back to the property name 
    	 */
    	for( String key : props.getNames() ) { 
    		if(  key.hashCode() == Integer.parseInt(element_id)) { 
    			/* 
    			 * when found the 'key' contains the looked for property name 
    			 */
    	    	Logger.debug("Changing property: '%s'; old value: '%s'; new value: '%s'", element_id, original_html, update_value);
    	    	props.setProperty(key, update_value);
    	    	/* 
    	    	 * render back the updated value as confirmation and exit 
    	    	 */
    	    	renderText(update_value);
    	    	return; 
    		}
    	}
    	
    	renderText(String.format("ERROR: cannot find property with id: %s", element_id));
    }
    
    	
	/**
	 * Renders the System information page 
	 */
	public static void sysinfo() {
		
		String sStartTime = "--";
		Long lStartTime = (Long) Cache.get("server-start-time");
		if( lStartTime != null ) { 
			Date dStartTime = new Date(lStartTime);
			sStartTime = Utils.asString(dStartTime);
			
			long delta = System.currentTimeMillis() - lStartTime;
			sStartTime += " (" + Utils.asDuration(delta) + " ago)"; 
		}
		
		TreeMap<Object,Object> map1 = new TreeMap<Object,Object>(System.getProperties());
		TreeMap<String,String> map2 = new TreeMap<String,String>(System.getenv());
		TreeMap<Object,Object> map3 = new TreeMap<Object,Object>( Play.configuration );

		render(map1,map2,map3,sStartTime);
	}
    
	/**
	 * Let the administrator edit the specified text file 
	 * @throws IOException 
	 */
	public static void editfile(final String file, String content) throws IOException {
		if( isGET() ) {
			/* 1. by default let's edit the main application conf file */
			String fullName;
			// if the specified fileName is not absoulute (does not start with '/') 
			// consider it relative to data root 
			if( !file.startsWith("/") ) {
				File abs = new File(AppProps.instance().getDataPath(), file);
				fullName = Utils.getCanonicalPath(abs);
			}
			else {
				fullName = file;
			}
			
			/* 2. read the file */
			content = FileUtils.readFileToString(new File(fullName), "utf-8");
			
			/* 3. render the edit page */
			render(fullName, content);
		}
		
		if( isPOST() ) {
			Check.notEmpty(file, "Argument 'fileName' cannot be empty");
			Check.notNull(content, "Argument 'fileContent' cannot be null");
			
			/* 1. backup the old file if exits */
			String backup = null;
			File target = new File(file);
			if( target.exists() ) {
				backup = file + ".bak";
				FileUtils.copyFile(target, new File(backup));
			}
			
			/* 2. save the new file */
			FileUtils.writeStringToFile(target, content, "utf-8");
			
			/* 3. go to confirm page */
			PageContent page = new PageContent();
			page.title = "File saved";
			page.description = "Operation confirmation";
			page.addParagraph("Edited file has been saved successfully");
			if( backup != null ) {
				page.addParagraph("Previous version has been stored as: %s", backup);
			}
			
			page.addAutoLink("@Admin.index", "Admin home");
			renderGenericPage(page);
		}
		
		unsupportedMethod();
	}
	
	/**
	 * Retuns perl runtime info
	 */
	public static void perlinfo() {
		
		String info;
		Process p1;
		try {
			p1 = Runtime.getRuntime().exec("perl -v");
			p1.waitFor();
			info = IO.readContentAsString(p1.getInputStream());
		} catch (Exception e) {
			Logger.error(e, "Error retrieving PERL version info");
			info = "(unable to get perl information)";
		}
		
		
		Process p2;
		String conf = null;
		try {
			p2 = Runtime.getRuntime().exec("perl -V");
			p2.waitFor();
			conf = IO.readContentAsString(p2.getInputStream());
		} catch (Exception e) {
			Logger.error(e, "Error retrieving PERL version info");
			conf = "(unable to get perl configuration)";
		}
		
		render(info,conf);
	} 
	
	/**
	 * Display the current version of t-coffee information
	 */
	public static void tcoffeeinfo() {
		Pattern pattern = Pattern.compile("PROGRAM: T-COFFEE \\((\\S+)\\)" );
		
		String info = "(unknown)";
		String ver = "(unknown)";
		
		TCoffeeCommand tcoffee = new TCoffeeCommand();
		tcoffee.ctxfolder = new File(AppProps.instance().getDataPath(), ".tcoffee-ver");
		tcoffee.logfile = "info.txt";
		tcoffee.errfile = "err.txt";
		tcoffee.init();
		try {
			tcoffee.execute();
			
			/* 
			 * extract the tcoffee version 
			 */
			for( String line :  new FileIterator(tcoffee.getErrFile()) ) {
				Matcher m = pattern.matcher(line);
				if( m.matches() ) {
					ver = m.group(1); 
					break;
				}

			};
			
			/* 
			 * fecth the list of all installed services 
			 */
			info = IO.readContentAsString(tcoffee.getLogFile()).trim();
			
			String PREFIX = "#######   Compiling the list of available methods ... (will take a few seconds)";
			if( info.startsWith(PREFIX)) {
				info = info.substring(PREFIX.length()).trim();
			}
			
		} catch (Exception e) {
			Logger.error(e,"Unable to get t-coffee information");
			ver = "(unable to get t-coffee version info)";
		}
		
		
		render(ver, info);
	}	


	/**
	 * Show the list of installed bundles in the system 
	 */
	public static void bundleManager() { 
		BundleRegistry registry = BundleRegistry.instance(); 
		List<Bundle> bundles = registry.getBundles();
		List<String> loadingErrors = registry.errors; 
		render(bundles, loadingErrors);
	}
	
	/**
	 * Shows the bundle details information 
	 * 
	 * @param bundleName teh name of the {@link Bundle} instance for which shows information
	 * 
	 */
	public static void bundleDetails( String bundleName ) { 
		Bundle bundle = BundleRegistry.instance().get(bundleName);
		render(bundle);
	}
	
	public static void bundleDrop( String bundleName ) { 
		Bundle bundle = BundleRegistry.instance().get(bundleName);
		if( bundle == null ) 
		{ 
			throw new BundleException("Missing bundle '%s'", bundleName);
		}
		
		if( isGET() ) { 
			/*
			 * show the action confirm request
			 */
			render(bundleName);
		}
		
		if( isPOST() ) { 
			/* 
			 * remove the bundle and show confirmation feedback
			 */
			final BundleRegistry registry = BundleRegistry.instance();
			try { 
				registry.drop(bundle);
				renderArgs.put("success", true);
			}
			catch( Exception e ) { 
				Logger.error(e, "Error dropping bundle '%s'", bundleName);
				renderArgs.put("error", e.getMessage());
			}
			
			render(bundleName);
		}

		/* it should reach this */
		unsupportedMethod();
	}
	
	static String ERROR( String message ) { 
		return String.format("{error: '%s'}", JavaExtensions.escapeJavaScript(message));
	}
	
	public static void bundleUpload(String qqfile) { 
		Logger.debug("Bundle upload file name: '%s'", qqfile);
		final String BUNDLE_KEY = Session.current().getId() + "_bundleupload";
		Cache.delete(BUNDLE_KEY);
		
		File bundleZip = null;
		
		try {
			/* 
			 * 1. Stage
			 * copy the uploaded content to a temporary file 
			 * and return that name in the result to be stored in a hidden field
			 */

			bundleZip = File.createTempFile("bundle_", ".zip", AppProps.TEMP_PATH);
			OutputStream out = new BufferedOutputStream(new FileOutputStream(bundleZip));
			IO.write(new BufferedInputStream(request.body), out);
			out.close();
			
			/* default error result */
			Logger.debug("Bundle upload save file: '%s'", bundleZip);

			/* check that uploaded file is valid */
			if( bundleZip==null ) {
				throw new BundleException("Unable to retrieve uploaded bundle");
			}
			
			/* error condition: wtf is that file ? */
			if( !bundleZip.exists() ) {
				throw new BundleException("Bundle uploaded file does not exist: '%s'", bundleZip);
			}
			
			
			/*
			 * 2. Stage
			 * Unzip the bundle in a temporaty folder 
			 */
			File stagingRoot = File.createTempFile("bundle_",null, AppProps.TEMP_PATH);
			stagingRoot.delete();
			stagingRoot.mkdir();
			
			ZipFile zip = new ZipFile(bundleZip);
			Enumeration<ZipEntry> entries = (Enumeration<ZipEntry>) zip.entries();
			while( entries.hasMoreElements() ) { 
				ZipEntry entry = entries.nextElement();
				
				File file = new File(stagingRoot, entry.getName() );
				if(entry.isDirectory()) {
			        // Assume directories are stored parents first then children.
			        // This is not robust, just for demonstration purposes.
					if( !file.mkdirs() ) { 
						throw new BundleException("Unable to create path '%s' unzipping bundle: '%s'", file, qqfile);
					}
					continue;
				}
				else {
					if( !file.getParentFile().exists() ) {
						file.getParentFile().mkdirs();
					}
					
					BufferedOutputStream stream =  new BufferedOutputStream(new FileOutputStream(file));
					IO.write(zip.getInputStream(entry), stream);
					stream.close();
				} 

			}
			zip.close();
			// the zip file can be deleted after the content has been extracted 
			bundleZip.delete();
			
			  			
			/*
			 * 3. Stage 
			 * Locate the right path and read the bundle instance
			 */
			File bundlePath = null;
			if( BundleRegistry.isBundlePath(stagingRoot) ) { 
				bundlePath = stagingRoot;
			}
			else { 
				// find the first sub-dir, it should contains the bundle 
				File[] all =  stagingRoot.listFiles();
				if( all!=null ) for( File file : all ) { 
					if( file.isDirectory() && BundleRegistry.isBundlePath(file)) {
						bundlePath = file;
						break;
					}
				}
			}
			
			Bundle bundle = null;
			if( bundlePath != null ) { 
				bundle = Bundle.read(bundlePath);
			}
			
			if( bundle == null ) { 
				throw new BundleException("The uploaded file does not contains a recognized bundle file format");
			}

			/*
			 * 4. Verify bundle
			 */
			bundle.verify();
			
			
			String SUCCESS = String.format("{success:true, name: '%s', version: '%s', key: '%s' }",
						JavaExtensions.escapeJavaScript(bundle.name),
						JavaExtensions.escapeJavaScript(bundle.version),
						JavaExtensions.escapeJavaScript(BUNDLE_KEY)
			);
			
			Cache.set(BUNDLE_KEY, bundle);
			renderText(SUCCESS);
			
		}
		catch( Result result ) { 
			throw result;
		}
		catch( BundleException e) { 
			Logger.error(e.getMessage());
			renderText(ERROR(e.getMessage()));
		}
		catch( Exception e ) {
			Logger.error(e, "Unable to copy temporary upload file: '%s'", bundleZip);
			renderText(ERROR(Utils.cause(e)));
		}
	
	}
	
	/**
	 * Let to upload and install a new bundle in the system 
	 */
	public static void bundleInstall(String key) {
		Bundle bundle = (Bundle) Cache.get(key);
		BundleRegistry registry = BundleRegistry.instance();
		Bundle installed = registry.get(bundle.name);

		
		if( isGET() ) { 
			
			if ( installed != null && bundle.version.compareTo(installed.version)<0 ) { 
				String error = 
					"A bundle with the same name and higher version it is already installed. " +
					"Before install the uploaded version you need to remove the current version.";
				renderArgs.put("message", error);
				renderArgs.put("message_class", "box-error");
			}
			else if( installed != null && bundle.version.equals(installed.version)) { 
				String warn = 
					"A bundle with the same name and version it is already installed. " +
					"If you proceed the current version will be deleted and replaced by the uploaded one. " +
					"NOTE: the operation cannot be undone.";
				renderArgs.put("message", warn);
				renderArgs.put("message_class", "box-warn");
			}
			else if( installed != null ) { 
				String warn = 
					"A bundle with the same name it is already installed. " +
					"If you proceed the current version will be replaced by the uploaded one. ";
				renderArgs.put("message", warn);
				renderArgs.put("message_class", "box-warn");
				
			}

			/*
			 * ask for confirmation
			 */
			render(bundle);
		}
		
		
		if ( isPOST() ){
			Cache.delete(key);
			
			/* 
			 * 1. remove the current one if the version is the same 
			 */
			if( installed != null && installed.version.equals(bundle.version)) { 
				registry.drop(installed);
			}
			
			/*
			 * 2. move the new on bundle folder 
			 */
			final String name = bundle.name + "_" + bundle.version;
			File target = new File( AppProps.BUNDLES_FOLDER, name);
			while( target.exists() ) { 
				String randomName = name + "_" + Math.round(Math.random() * 1000); 
				target = new File( AppProps.BUNDLES_FOLDER, randomName); 
			}
			
			
			try {

				/*
				 * 3. move to final path 
				 */
				final File source = bundle.root;
				FileUtils.moveDirectory(source, target);

				/*
				 * 4. force installation 
				 */
				bundle = registry.load(target);
				if( source.exists() && !FileUtils.deleteQuietly(source) ) { 
					Logger.warn("Unable to remove bundle staging path: '%s'", source);
				}
				
				
				renderArgs.put("message", "Bundle installed successfully");
				renderArgs.put("message_class", "box-success");
			} 
			catch (Exception e) {
				String error = "Cannot install application bundle. " + e.getMessage();
				renderArgs.put("message", error);
				renderArgs.put("message_class", "box-error");
			}

			/* 
			 * 5. try to change permission for bin folder 
			 */
			try { 
				if( bundle.binPath != null ) { 
					File[] bins = bundle.binPath. listFiles();
					for( File bin : bins ) { 
						bin.setExecutable(true,false);
					}
				}			
				
			} catch( Exception e ) { 
				Logger.warn(e, "Unable to change to exec permission '%s'", bundle.binPath);
			}
			
			/* finally render the page */
			render(bundle);
		}

		unsupportedMethod();
	} 
	
	

	
	public static void bundleServiceEdit( String bundleName, String serviceName, String content ) { 
    	
		final BundleRegistry registry = BundleRegistry.instance();
		final Bundle bundle = registry.get(bundleName);

		if( isGET() ) { 
			/*
			 * show the edit form 
			 */
	        try {
	        	String conf = IO.readContentAsString(bundle.conf);
	        	content = BundleHelper.getServiceXml(conf, serviceName);

			} 
	        catch (Exception e) {
	        	throw new QuickException(e, "Fail on editing service '%s' on bundle '%s'", serviceName, bundleName);
			}
			
		}
		
		
		if( isPOST() ) { 
			/*
			 * save the changed service 
			 */
			try {
				// TODO if empty redirect to serviceDelete
				
				// 1. read the original xml 
	        	String conf = IO.readContentAsString(bundle.conf);
	        	conf = BundleHelper.replaceServiceXml(conf, content);
	        	
	        	// 2. save to a temporary file 
	        	File file = File.createTempFile("bundle-", ".xml", AppProps.TEMP_PATH);
	        	IO.writeContent(conf, file);
	        	
	        	// 3. try to load to check to nothing is broken 
	        	XStreamHelper.fromXML(file);
	        	
	        	// 4. if ok save to original file 
	        	IO.writeContent(conf, bundle.conf);
	        	
	        	String message = String.format("Service configuration saved successfully to file: '%s'", bundle.conf);
	        	renderArgs.put("message", message);
	        	renderArgs.put("messageClass", "box-info");
			} 
			catch( Exception e ) { 
				Logger.error(e, "Error saving service conf '%s' for bundle '%s'", serviceName, bundleName);
				
				String message = "Error saving service configuration. Cause: " + Utils.cause(e);
	        	renderArgs.put("message", message);
	        	renderArgs.put("messageClass", "box-error");
				
			}
			
		}
		
		render("Admin/editxml.html", bundleName, serviceName, content);
		
	}
	
	
	
	public static void bundleServiceDelete( String bundleName, String serviceName ) { 
		
		
	}
	
	
	/**
	 * Creates a copy of the service XML configuration 
	 * 
	 * @param bundleName the unique {@link Bundele} name
	 * @param serviceName the new name for the copied service
	 * @param content when invoked in GET mode the name of the service to be copied, 
	 * in POST mode the xml service fragment to be saved  
	 */
	public static void bundleServiceCopy( String bundleName, String serviceName, String newName, String content ) { 

    	
		final BundleRegistry registry = BundleRegistry.instance();
		final Bundle bundle = registry.get(bundleName);

		if( isGET() ) { 
			/*
			 * show the edit form 
			 */
	        try {
	        	String conf = IO.readContentAsString(bundle.conf);
	        	Element elem = BundleHelper.getServiceElement(conf, serviceName);
	        	Attribute attr = elem.attribute("name");
	        	attr.setValue( newName );
	        	
	        	content = elem.asXML();
	        	
	        	render("Admin/editxml.html", bundleName, serviceName, newName, content);
			} 
	        catch (Exception e) {
	        	throw new QuickException(e, "Fail on editing service '%s' on bundle '%s'", serviceName, bundleName);
			}
			
		}
		
		
		if( isPOST() ) { 
			try { 
				//TODO verify that thare is a name conflic !
				
				// 1. read the conf file 
	        	String conf = IO.readContentAsString(bundle.conf);
				
	        	// 3. add the new fragment 
	        	conf = BundleHelper.addServiceXml(conf, content);
	        	
	        	// 4. save to a temporary file 
	        	File file = File.createTempFile("bundle-", ".xml", AppProps.TEMP_PATH);
	        	IO.writeContent(conf, file);
	        	
	        	// 5. try to load to check to nothing is broken 
	        	XStreamHelper.fromXML(file);
	        	
	        	// 6. if ok save to original file 
	        	IO.writeContent(conf, bundle.conf);
	        	
	        	String message = String.format("Service configuration saved successfully to file: '%s'", bundle.conf);
	        	renderArgs.put("message", message);
	        	renderArgs.put("messageClass", "box-info");
  	
			}
			catch( Exception e ) { 
				Logger.error(e, "Error saving service conf '%s' for bundle '%s'", serviceName, bundleName);
				
				String message = "Error saving service configuration. Cause: " + Utils.cause(e);
	        	renderArgs.put("message", message);
	        	renderArgs.put("messageClass", "box-error");
				
			}
			
        	render("Admin/editxml.html", bundleName, serviceName, newName, content);
		
		}
		
	}
	
	
	/**
	 * Zip all the bundle content and return the binary file to download
	 * 
	 * @param bundleName the bundle name of pack and download
	 */
	public static void bundleZipAndDownload( String bundleName ) { 
		final BundleRegistry registry = BundleRegistry.instance();
		final Bundle bundle = registry.get(bundleName);
		
		try {
			File zipFile = File.createTempFile("bundle-", ".zip", AppProps.TEMP_PATH);
			
			ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile));

			final String base = bundle.root.getParent().toString();
			Iterator i = FileUtils.iterateFiles(bundle.root, null, true);
			while( i.hasNext() ) { 
				File file = (File) i.next();
				
				String name = file.toString();
				if( name.startsWith(base)) { 
					name = name.substring(base.length());
				}
				ZipEntry entry = new ZipEntry(name);
				zip.putNextEntry(entry);
				
				if( file.isFile() ) { 
					// append the file content
					FileInputStream in = new FileInputStream(file);
					IO.write(in, zip);
					in.close(); 		
				}
				// Complete the entry 
				zip.closeEntry(); 
			}
			
			zip.close();
			
			String attachName = String.format("bundle-%s-%s.zip", bundle.name, bundle.version);
			response.setHeader("Content-Disposition", "attachment; filename=\"" + attachName+ "\"");
			renderBinary(zipFile);
		}
		catch (IOException e) {
			throw new QuickException(e, "Unable to zip content for bundle: '%s'", bundle.name);
		}		
	}
	

	/** 
	 * Show the Usage log file download page
	 */
	public static void viewLogFiles() { 
		/*
		 * find out current defined loggers 
		 */
		Enumeration items = Logger.log4j.getLoggerRepository().getCurrentLoggers();
		Map<String,String> loggers = new HashMap<String,String>();
		while( items.hasMoreElements() ) { 
			org.apache.log4j.Logger logger = (org.apache.log4j.Logger) items.nextElement();
			if( logger.getLevel() != null ) { 
				loggers.put(logger.getName(), logger.getLevel().toString());
			}
		}
		loggers.put("rootLogger", Logger.log4j.getRootLogger().getLevel().toString());

		renderArgs.put("loggers", loggers);
		
		/* the usage file */
		
		renderArgs.put("usageLogFile", AppProps.SERVER_USAGE_FILE.exists() ? AppProps.SERVER_USAGE_FILE.getAbsoluteFile() : null );
		
		/* the application log file */
		renderArgs.put("appLogFile", AppProps.SERVER_APPLOG_FILE != null && AppProps.SERVER_APPLOG_FILE.exists() ? AppProps.SERVER_APPLOG_FILE.getAbsolutePath() : null);
		
		/* render the page */
		render();
	}
	
	  
    /**
     * This method is invoked when a property value is update using by an ajax request.
     * <p>
     * Please NOTE: changing parameters name will break the code (they must match the javascript plugin definition)
     * </p>
     * 
     * 
     * @param element_id the property key hash code
     * @param original_html the previous property value 
     * @param update_value the new property value
     */
    public static void updateLogger( String element_id, String original_html, String update_value) { 
    	Logger.debug("updateLogger(%s, %s, %s)", element_id, original_html, update_value);
    	
    	if( Utils.isEmpty(element_id )) { 
    		return;
    	}
    	
    	/* 
    	 * replace special char '|' used to replace dots in logger identificator 
    	 */
    	final String loggerName = element_id.replace('|', '.');
    	final String newLevel = update_value; 

    	/* get the logger instance */
    	org.apache.log4j.Logger logger=null;
    	if( "rootLogger".equals( loggerName )) { 
        	logger = Logger.log4j.getRootLogger();
    	}
    	else { 
    		logger = Logger.log4j.getLogger(loggerName);
    	}
    	
    	/* update level */
    	if( logger != null ) { 
    		logger.setLevel(Level.toLevel(newLevel));
    		
    		/* render back the updated value as confirmation */
    		Logger.info("Logger '%s' switched to level '%s' ", loggerName, newLevel);
    		renderText(update_value);

    	}
    	else { 
    		Logger.warn("Unable to update logger level. Unknown logger name: '%s'", loggerName);
			renderText("Invalid logger : " + loggerName);
    	}
    	
    }
	
	/**
	 * Invoke to download the usage log file
	 */
	public static void downloadUsageLog() { 
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String filename = "tserver-usage-"+fmt.format(new Date())+".log";
		response.setHeader("Content-Disposition", "attachment; filename=\""+filename+"\"");
		renderBinary( AppProps.SERVER_USAGE_FILE );
	}

	public static void downloadApplicationLog() { 

		if( AppProps.SERVER_APPLOG_FILE == null ) { 
			notFound("Cannot find file: %s", AppProps.SERVER_APPLOG_FILE);
		}
		
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String filename = "tserver-application-"+fmt.format(new Date())+".log";
		response.setHeader("Content-Disposition", "attachment; filename=\""+filename+"\"");
		renderBinary( AppProps.SERVER_APPLOG_FILE );
		
	}
	
	public static void previewUsageLog() throws IOException 
	{
		String sMax = Play.configuration.getProperty("preview.max.bytes", "3072");
		long lMax = Utils.parseLong(sMax, 3072L);
		renderText( tail( AppProps.SERVER_USAGE_FILE, lMax ) );
	} 
	
	public static void previewApplicationLog() throws IOException 
	{
		String sMax = Play.configuration.getProperty("preview.max.bytes", "3072");
		long lMax = Utils.parseLong(sMax, 3072L);
		renderText( tail( AppProps.SERVER_APPLOG_FILE, lMax ) );
	} 
	
	public static class SysMessage implements Serializable { 
		public String value;
		public String type;

		public SysMessage( String message, String type ) { 
			this.value = message;
			this.type = type;
		}
		
		public int hashCode() { 
			int result = Utils.hash();
			result = Utils.hash(result, value);
			result = Utils.hash(result, type);
			return result;
		}
		
		public String getHash() { 
			return String.valueOf(hashCode());
		}
	}
	
	/**
	 * Publish a system message to all users 
	 */
	public static void sysmsg( String message, String type, String duration ) { 
		Field fieldMessage = new Field("textarea", "message");
		fieldMessage.label = "Message to publish";
		fieldMessage.hint = "Enter here the message to publish";

		Field fieldType = new Field("dropdown", "type");
		fieldType.label = "Type";
		fieldType.hint = "The type of the message";
		fieldType.choices = new String[] { "box-info", "box-success", "box-warn", "box-error" };
		
		
		Field fieldExpires = new Field("text", "duration");
		fieldExpires.label = "Duration";
		fieldExpires.hint = "Specify how long this message will be visible e.g 10min, 1h, 1d";
		
		/*
		 * is POST apply the changes 
		 */
		if( isPOST() ) { 
			String feedback;
			if( Utils.isEmpty(message)) { 
				Cache.delete("sysmsg");
				feedback = "System message removed";
			}
			else { 
				if( Utils.isEmpty(duration) ) { duration = null; }
				Cache.safeSet("sysmsg", new SysMessage(message,type), duration);
				feedback = "System message published";
			}
			
			renderArgs.put("feedback", feedback);
		}
		
		/* default values */
		SysMessage sysmsg = (SysMessage) Cache.get("sysmsg");
		fieldMessage.value =  sysmsg != null ? sysmsg.value : null;
		fieldType.value = type != null ? type : "box-warn";
		
		render(fieldMessage, fieldType, fieldExpires);
	}
	
	/**
	 * Render the page for to Manage Users 
	 * 
	 */
	public static void usersList() { 
		renderArgs.put("users", USERS_LIST.get());
		render();
	}
	
	/**
	 * Handle ajax request actions on users page.
	 * <p>
	 * NOTE: parameters name must mach as specified by the editinplace plugin
	 * 
	 * @param element_id the element submiting the data 
	 * @param original_html the previous value (not meaningful)
	 * @param update_value the user account
	 */
	public static void userUpdate( String element_id, String original_html, String update_value ) { 
		final String sAction = element_id;
		final String sUser = update_value != null ? update_value.trim() : null;
		
		String result = update_value; // send back this value as confirmation
		
		try { 
			List<String> items = USERS_LIST.get();
			if( "add" .equals(sAction) ) { 
				if( !items.contains(sUser) ) { 
					items.add(sUser);
					/* save  the new one */
					PrintWriter out = new PrintWriter(new FileWriter(USERS_FILE,true));	// <-- true to append 
					out.println(sUser);
					out.close();
				}
				
			}
			else if( "del" .equals(sAction) ) { 
				if( items.remove(sUser) ) { 
					/* save the updated list */
					PrintWriter out = new PrintWriter(new FileWriter(USERS_FILE,false)); // <-- false to OVERWRITE
					for( String uu : items ) { 
						out.println(uu);
					}
					out.close();
				}
			}
			
		}
		catch( Exception e ) { 
			Logger.error(e, "Failing on user action: '%s'; '%s'", sAction, sUser );
			result = "ERROR";
		}
		
    	/* render back the updated value as confirmation */
    	renderText(result);
		
	}
	
	private static String tail( File file, long maxBytes ) throws IOException { 
		RandomAccessFile handler = new RandomAccessFile(file, "r");
		long delta ;
		if( (delta=handler.length()-maxBytes)>0 ) { 
			handler.seek(delta);
			// consume first line that may not start from beginning
			handler.readLine();
		}
		
		StringBuilder result = new StringBuilder();
		String line;
		while( (line=handler.readLine()) != null ) { 
			result.append(line) .append("\n");
		}
		
		return result.toString();
	}
	
 }

