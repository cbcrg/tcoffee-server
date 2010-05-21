package util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import models.AppConf;
import models.Field;
import models.Fieldset;
import models.Input;
import models.Module;
import play.libs.IO;
import exception.QuickException;

/**
 * Test helper class 
 * 
 * @author Paolo Di Tommaso
 *
 */
public class TestHelper {

	public static Module module() {
		return module(new String[]{});
	}
	
	public static Module module(String ... fieldsAndValues) {
		Module module = new Module();

		AppConf conf = new AppConf();
		conf.modules = new ArrayList<Module>();
		conf.modules.add(module);
		module.conf = conf;
	
		Fieldset set = new Fieldset();

		for( String pair : fieldsAndValues ) {
			String[] items = pair.split("=");
			set.add(new Field("text",items[0], items.length>1 ? items[1] : ""));
		}
		
		module.input = new Input();
		module.input.fieldsets().add(set);
		module.prepare();
		return Module.current(module);		
	}

	public static File file(String file) {
		return new File(TestHelper.class.getResource(file).getFile());
	}

	public static File sampleLog() {
		return file("/sample-tcoffee.log");
	}

	public static File sampleFasta() {
		return file("/sample.fasta");
	}
	
	public static File sampleClustal() {
		return file("/sample-clustalw.txt");
	}
	
	public static void copy( File source, File target ) {
		Check.isTrue(source.exists(), "The source file does nto exists: %s", source);
		Check.isTrue(source.isFile(), "The source is not a file: %s", source);

		File folder; 
		if( target.isDirectory() ) {
			folder = target;
		}
		else {
			folder = target.getParentFile();
		}
		
		if( !folder.exists() ) {
			Check.isTrue( folder.mkdirs(), "Unable to create destination folder: %s", folder );
		}
		
		if( target.isDirectory() ) {
			target = new File(target, source.getName());
		}

		try {
			InputStream in = new FileInputStream(source);
			OutputStream out = new FileOutputStream(target);
			IO.write(in, out);
			out.close();
		} catch( IOException e ) {
			throw new QuickException(e, "Unable to copy '%s' --> '%s'", source,target);
		}

	}
	
	public static int randomHash() {
		return new Double(Math.random()).hashCode();
	}
	
	public static String randomHashString() {
		return Integer.toHexString(randomHash());
	}
	
}
