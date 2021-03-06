package controllers;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import play.Logger;
import play.Play;
import play.libs.IO;
import play.libs.Mail;
import play.mvc.Controller;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.test.TestEngine;

/**
 * This controller is required only to run test in headless mode. 
 * Not used in production mode.
 * 
 * @author Paolo Di Tommaso
 *
 */
public class SlimTestRunner extends Controller {

    public static void index() {

    	List<Class> unitTestsList = TestEngine.allUnitTests();
        List<Class> functionalTestsList = TestEngine.allFunctionalTests();
 

          
        /* 
         * run al tests 
         */
        Map<String,String> unitTests = new TreeMap<String, String>( );
        Map<String,String> functionalTests = new TreeMap<String, String>( );
        
        // initialization 
        boolean passed = true;
        init();
        
        // run unit tests 
        for( Class clazz : unitTestsList ) {
        	boolean result = run(clazz.getName());
        	unitTests.put(clazz.getName(), result ? "passed" : "failed");
        	passed = passed && result;
        }

        // run functional tests 
        for( Class clazz : functionalTestsList ) {
        	boolean result = run(clazz.getName());
        	functionalTests.put(clazz.getName(), result ? "passed" : "failed");
        	passed = passed && result;
        }
        
        
        // finalization 
       	String result = passed ? "passed" : "failed";
        finalize(result);
        
        /* 
         * no render the result
         */
        render(result, unitTests, functionalTests);
    }

    static void init() {
        File testResults = Play.getFile("test-result");
        if (!testResults.exists()) {
            testResults.mkdir();
        }
        for(File tr : testResults.listFiles()) {
            if ((tr.getName().endsWith(".html") || tr.getName().startsWith("result.")) && !tr.delete()) {
                Logger.warn("Cannot delete %s ...", tr.getAbsolutePath());
            }
        }
    }

    static void finalize( String result ) {
        File testResults = Play.getFile("test-result/result." + result);
		IO.writeContent(result, testResults);
    }

    static boolean run(String test)  {

        Play.getFile("test-result").mkdir();
        try {
			java.lang.Thread.sleep(250);
		} catch (InterruptedException e) {
			Logger.warn("InterrupedException on test run sleep");
		}
        TestEngine.TestResults results = TestEngine.run(test);

        Template resultTemplate = TemplateLoader.load("SlimTestRunner/results.html");
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("test", test);
        options.put("results", results);
        String result = resultTemplate.render(options);
        File testResults = Play.getFile("test-result/" + test + ".java" + (results.passed ? ".passed" : ".failed") + ".html");
		IO.writeContent(result, testResults);

        return results.passed;
    }

     public static void mockEmail(String by) {
        String email = Mail.Mock.getLastMessageReceivedBy(by);
        if(email == null) {
            notFound();
        }
        renderText(email);
    }

}

