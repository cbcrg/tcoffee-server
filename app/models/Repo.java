package models;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;

import play.Logger;
import util.Check;
import util.Utils;
import util.XStreamHelper;
import edu.emory.mathcs.backport.java.util.Arrays;
import exception.QuickException;

public class Repo {
	
	private static final String MARKER_FILE_NAME = ".tserver";

	private static final String RESULT_FILE_NAME = ".result";

	private static final String LOCK_FILE_NAME = ".lock";


	String rid;
	
	File fFolder;
	
	File fLock;
	
	File fResult;

	File fMarker;
	
	/** 
	 * Create the context folder on the file system for the specified <i>requets identifier</i> 
	 * */
	public Repo( final String rid ) {
		this(rid,false);
	}

	public Repo( final String rid, final boolean create ) {
		this(new File(AppProps.instance().getDataPath(),rid),create);
	}
	
	protected Repo( final File folder, final boolean create ) {
		Check.notNull(folder,"Repo folder cannot be null");
		
		this.fFolder = folder;
		this.fLock = new File(fFolder,LOCK_FILE_NAME);
		this.fResult = new File(fFolder,RESULT_FILE_NAME);
		this.fMarker = new File(fFolder,MARKER_FILE_NAME);
		this.rid = folder.getName();
		
		if(create) {
			create(fFolder);
		}
		
	}
	
	
	/** The copy constructor */
	public Repo( Repo that ) {
		this.rid = that.rid;
		this.fFolder = that.fFolder;
		this.fLock = that.fLock;
		this.fMarker = new File(fFolder,MARKER_FILE_NAME);
		this.fResult = that.fResult;
	}
	
	@Override
	public boolean equals( Object that ) {
		return Utils.isEquals(this, that, "rid", "fFolder", "fLock", "fMarker", "fResult");
	}
	
	@Override
	public int hashCode() {
		return Utils.hash(this, "rid", "fFolder", "fLock", "fMarker", "fResult");
	}

	/** Locks the current context folder */
	public boolean lock() {
		
		//TODO check if FileLock api does a better work 
		
		try {
			return fLock.createNewFile();
		}
		catch( IOException e ) {
			/* otherwise is an unexecptected condition */
			throw new QuickException("Unable to create .lock file for context folder '%s'", rid); 
		}
		
	}
	
	/** Release the lock on the context folder */
	public void unlock() {

		if( !fLock.exists() ) {
			Logger.warn(".lock file does not exist for context folder '%s'", rid);
			return;
		}
		
		if( !fLock.delete() ) {
			throw new QuickException("Unable to delete .lock file for context folder '%s'", rid);
		}
	}
	
	public File getFile() {
		return fFolder;
	}
	
	public Status getStatus() {
		if( !fFolder.exists() || !fMarker.exists()) {
			return Status.UNKNOWN;
		}
		
		if( fLock.exists() ) {
			return Status.RUNNING;
		}
		
		if( fResult.exists() ) {
			try {
				OutResult result = XStreamHelper.fromXML(fResult);
				return result.status;
			} 
			catch( Exception e ) {
				Logger.warn(e, "Error on parsing result file: '%s'", fResult);
				return Status.UNKNOWN;
			}
		}
		
		//TODO review this state. Is it inconsistent? 
		return Status.INIT;
	}
	
	public boolean isTerminated() {
		Status status = getStatus();
		return Status.DONE.equals(status) || Status.FAILED.equals(status); 
	}
	
	public boolean hasResult() {
		return fResult != null && fResult.exists() && isTerminated();
	}
	
	public boolean isExpired(Status status) {
		long elapsedSecs = (System.currentTimeMillis() - fFolder.lastModified()) / 1000;
		
		if( status.isFailed() && elapsedSecs>30 ) {
			/* after 30 secs mark this repo as expired */
			return true;
		}
		
		return elapsedSecs > AppProps.instance().getRequestTimeToLive(); 		
	}

	public boolean isExpired() {
		return isExpired(getStatus());
	}
	
	/**
	 * @return the {@link OutResult} instance contained in this Repo
	 * 
	 */

	public OutResult getResult() {
		return XStreamHelper.fromXML(fResult);
	}
	
	/**
	 * Serialize the specified instance of {@link OutResult}
	 * @param out {@link OutResult} instance to save in this report 
	 */
	public void saveResult( OutResult out ) {
		XStreamHelper.toXML(out, fResult);
	} 
	
	File create( File folder ) {
		if( folder.exists() ) {
			Logger.warn("Well, cannot create an already existing folder: '%s'", folder.toString());
			return folder;
		}
		
		boolean result = folder.mkdirs();
		if( !result ) {
			throw new QuickException("Fail on creating repo folder: '%s'", folder);
		}
		
		if( fMarker.exists() ) {
			return folder;
		} 

		RuntimeException fail=null;  
		try {
			if( !fMarker.createNewFile() ) {
				fail = new QuickException("Unable to create repo marker file: '%s'", fMarker);
			}
		} catch (IOException e) {
			fail = new QuickException(e, "Fail on creating repo marker file: '%s'", fMarker);
		}
		
		if( fail != null ) throw fail;
		
		return folder;		
	}
	
	void touch( Date date ) {
		fFolder.setLastModified(date.getTime());
	}
	
	void touch() {
		touch(new Date());
	}
	
	void touch( long millis ) {
		fFolder.setLastModified(millis);
	}
	
	/**
	 * Remove all the content of the current repo path with the exception of the marked file. See {@link #fMarker}.
	 */
	public void clean() {
		
		File[] all = fFolder.listFiles();
		if( all!=null ) for( File file : all ) {
			
			if( !fMarker.equals(file) ) {
				if( !FileUtils.deleteQuietly(file) ) {
					Logger.warn("Unable to clean file: '%s'", file);
				}
			}
		}
		
		// reset the current folder timestamp
		touch();
	}
	

	/**
	 * Drop this folder and all its content
	 */
	public void drop() {
		drop(false);
	}
	

	public void drop(boolean forceKill) {
		
		if( forceKill ) {
			/* 
			 * kill all pending process that are locking the folder 
			 * An alternative command to kill all process is: "fuser -k <file>" 
			 * but the -k switch (kill) is not supported on OSX 
			 * 
			 * kill -9 `lsof -t %s`
			 */

			try {
				//TODO win32 porting: rewrite this command to support Windows platform 
				String kill = String.format("kill -9 `lsof -t +D %s`", fFolder);
//				DefaultExecutor exec = new DefaultExecutor();
//				exec.setWatchdog( new ExecuteWatchdog(5000) ); // <-- 5secs timeout to run kill all pending  
//				exec.execute(new CommandLine(kill));
				Process proc = Runtime.getRuntime().exec(kill);
				proc.waitFor();
				
				
			} catch( Exception e ) {
				Logger.warn(e, "Error kill pending process for folder: '%s' ", fFolder);
			}
			
		}

		/*
		 * remove all files 
		 */
		if( !FileUtils.deleteQuietly(fFolder) ) {
			Logger.error("Unable to delete folder: '%s'", fFolder);
		}
	}
	
	/**
	 * Find all repository instance being in one of the specified status 
	 * 
	 * @param status open array of the requested status of <code>null</code> for any status 
	 * @return the list of {@link Repo} in the requested status or a empty list if nothing is found 
	 */
	public static List<Repo> findByStatus(Status ... status) {
		List<Repo> result = new ArrayList<Repo>();
		
		File ROOT_FOLDER = new File( AppProps.instance().getDataPath() );
		for( File file : ROOT_FOLDER.listFiles() ) {

			if( isRepoFolder(file) ) {
				Repo repo=new Repo(file,false);
				if( status == null || Utils.contains(status, repo.getStatus()) ) {
					result.add(repo);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * Find all the repo objects in any status 
	 */
	public static List<Repo> findAll() {
		return findByStatus((Status[])null);
	}

	/**
	 * Find all the repo eclusing the ones being the specified status 
	 * 
	 * @param exception an open array of the status to be excluded in the result list 
	 * @return a list of {@link Repo} instances or an empty list if nothing is found 
	 */
	public static List<Repo> findAllExcept(Status... exception) {
		List<String> status = Arrays.asList(Status.values());

		if( exception != null )
			status.removeAll( Arrays.asList(exception) );
		
		return findByStatus((Status[])status.toArray());
	}
	
	/**
	 * @param folder a not null file referencing a folder on file system
	 * @return <code>true</code> if it is a valid tcoffee job context repository or <code>false</code> otherwise 
	 */
	public static boolean isRepoFolder( File folder ) {
		return folder.exists() && folder.isDirectory() && new File(folder,MARKER_FILE_NAME).exists();
	}

	/**
	 * Remove all repos from the file system in the specified status 
	 *  
	 * @param status an open array of status begin in which the repo have to be deleted
	 */
	public static void deleteByStatus(Status... status) {
		List<Repo> repos = Repo.findByStatus(status);
		
		for( Repo repo : repos ) {
			Logger.info("Deleting Repo folder: '%s'", repo.getFile());
			/* drop this folder */
			repo.drop();
		}
	}
	
	/**
	 * Remove all status regardless the status in which the repo is  
	 */
	public static void deleteAll() {
		deleteByStatus((Status[])null);
	}

	/**
	 * Delete all the expired jobs
	 */
	public static void deleteExpired() {
		List<Repo> all = findAll();
		for( Repo repo : all ) {
			if(repo.isExpired()) {
				repo.drop(true);
			}
		}
	}
	

}
