/*
 * Vhdl RuleChecker (Vhdl-rc) plugin for Sonarqube & Zamiacad
 * Copyright (C) 2019 Maxime Facquet
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.linty.sonar.plugins.vhdlrc;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.io.FileUtils;
import org.fest.util.VisibleForTesting;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.config.Configuration;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import com.linty.sonar.plugins.vhdlrc.issues.ExternalReportProvider;
import com.linty.sonar.plugins.vhdlrc.issues.Issue;
import com.linty.sonar.plugins.vhdlrc.issues.ReportXmlParser;
import com.linty.sonar.zamia.BuildPathMaker;
import com.linty.sonar.zamia.ZamiaRunner;

public class VhdlRcSensor implements Sensor {
	public static final String SCANNER_HOME_KEY ="sonar.vhdlrc.scanner.home";
	public static final String      PROJECT_DIR = "rc/Data/workspace/project";
	public static final String   REPORTING_PATH = PROJECT_DIR + "/rule_checker/reporting/rule";
	public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");
	public static final String RC_SYNTH_REPORT_PATH = IS_WINDOWS ? ".\\report_" : "./report_";
	public static final String SOURCES_DIR = "vhdl";
	public static final String REPORTING_RULE = "rule_checker/reporting/rule";
	private static final String repo="vhdlrc-repository";
	private static final String fexplicit=" -fexplicit";
	private static final String fsynopsys=" -fsynopsys";
	private static final String yosysFsmCmd1="yosys -m ghdl -p \"ghdl";
	private static final String yosysFsmCmd2="; setattr -set fsm_encoding \\\"auto\\\"; fsm -norecode -nomap -export\"";
	private static final Logger LOG = Loggers.get(VhdlRcSensor.class);
	private static List<String> unfoundFiles = new ArrayList<>();
	private String fsmRegex;
	private SensorContext context;
	private FilePredicates predicates;
	private String baseProjDir;

	@Override
	public void describe(SensorDescriptor descriptor) {
		descriptor
		.name("Import of RuleChecker Xml Reports")
		.onlyOnLanguage(Vhdl.KEY)
		.name("vhdlRcSensor")
		.onlyWhenConfiguration(conf -> conf.hasKey(SCANNER_HOME_KEY));
	}

	@Override
	public void execute(SensorContext context) {
		this.context=context;
		this.predicates = context.fileSystem().predicates();
		Configuration config = context.config();
		baseProjDir=System.getProperty("user.dir");
		//ZamiaRunner-------------------------------------------------------
		String top=BuildPathMaker.getTopEntities(config);
		if(top.isEmpty()) {
			LOG.warn("Vhdlrc analysis skipped : No defined Top Entity. See " + BuildPathMaker.TOP_ENTITY_KEY);
			LOG.warn("Zamia Issues will still be imported");
		} else {
			ZamiaRunner.run(context); 
		}
		//------------------------------------------------------------------
		if(BuildPathMaker.getAutoexec(config)) {			
			String fileList=BuildPathMaker.getFileList(config);
			String rcSynth = BuildPathMaker.getRcSynthPath(config);
			String ghdlParams=((BuildPathMaker.getFexplicit(config)) ? fexplicit : "")+((BuildPathMaker.getFsynopsys(config)) ? fsynopsys : "");	
			String yosysFsmCmd = yosysFsmCmd1+ghdlParams+" "+top+" "+yosysFsmCmd2;
			String workdir=BuildPathMaker.getWorkdir(config);
			if(IS_WINDOWS) {
				System.out.println(executeCommand(new String[] {"cmd.exe","/c","ubuntu1804 run "+rcSynth+" "+top+" \""+ghdlParams+"\""+" \""+fileList+"\""})); // Still needs work
				//System.out.println(executeCommand(new String[] {"cmd.exe","/c","cd "+BuildPathMaker.getWorkdir(config)+"; ubuntu1804 run "+yosysFsmCmd}));
				try {
					Runtime.getRuntime().exec("cmd.exe /c cd "+workdir+"; ubuntu1804 run \"+yosysFsmCmd").waitFor();
				} catch (IOException | InterruptedException e) {
					LOG.warn("Ubuntu thread interrupted");
					Thread.currentThread().interrupt();
				}
			}
			else {
				String[] cmd = new String[] {"sh","-c","bash "+rcSynth+" "+top+" \""+ghdlParams+"\""+" \""+fileList+"\""};
				System.out.println(executeCommand(cmd));
				System.out.println(executeCommand(new String[] {"sh","-c","cd "+workdir+"; "+yosysFsmCmd}));
			}
		}

		try {
			Files.walk(Paths.get(context.fileSystem().baseDir().getAbsolutePath())).filter(Files::isRegularFile).filter(o->o.toString().toLowerCase().endsWith(".kiss2")).forEach(o1->addYosysIssues(o1)); //Could be better optimized by using workdir property
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		Path reportsDir = Paths
				.get(config
						.get(SCANNER_HOME_KEY)
						.orElseThrow(() -> new IllegalStateException("vhdlRcSensor should not execute without " + SCANNER_HOME_KEY)))
				.resolve(REPORTING_PATH);
		List<Path> reportFiles = ExternalReportProvider.getReportFiles(reportsDir);
		Path rcSynthReport = Paths.get("./");
		List<Path> rcReportFiles = ExternalReportProvider.getReportFiles(rcSynthReport);
		rcReportFiles.removeIf(o->!o.toString().startsWith(RC_SYNTH_REPORT_PATH));
		if(!rcReportFiles.isEmpty())
			reportFiles.addAll(rcReportFiles);
		reportFiles.forEach(report -> importReport(report, context));
		unfoundFiles.forEach(s -> LOG.warn("Input file not found : {}. No rc issues will be imported on this file.",s));

		String scannerHome= context.config()
				.get(VhdlRcSensor.SCANNER_HOME_KEY)
				.orElseThrow(() -> new IllegalStateException("vhdlRcSensor should not execute without " + VhdlRcSensor.SCANNER_HOME_KEY));
		if(!BuildPathMaker.getKeepSource(config)) {
			ZamiaRunner.clean(Paths.get(scannerHome, PROJECT_DIR, SOURCES_DIR));
		}
		if(!BuildPathMaker.getKeepReports(config)&&!context.fileSystem().baseDir().toString().equals("src\\test\\files")) { //Second condition is here to avoid deletion of test files
			Path reportPath = Paths.get(scannerHome, PROJECT_DIR, REPORTING_RULE);
			ZamiaRunner.clean(reportPath);
			try {
				DirectoryStream<Path> dstream = Files.newDirectoryStream(Paths.get(scannerHome, PROJECT_DIR, REPORTING_RULE));
				if (dstream.iterator().hasNext() ) {  // Zamiarunner.clean, which uses FileUtils.cleanDirectory, doesn't always delete files in subfolders
					FileUtils.forceDeleteOnExit(reportPath.toFile());
				}
				dstream.close();
			} catch (IOException e) {
				LOG.warn("Error while trying to clean reports directory");
			}
		}
	}


	private void addYosysIssues(Path kiss2Path) {

		fsmRegex = null;
		ActiveRule cne_02000 = context.activeRules().findByInternalKey(repo, "CNE_02000");
		if (cne_02000!=null) {
			String format = cne_02000.param("Format");
			if(format!=null) {
				if(!format.startsWith("*"))
					format="^"+format;
				fsmRegex=format.trim().replace("*", ".*");
			}
		}


		String[] kiss2FileName =kiss2Path.getFileName().toString().split("-\\$fsm\\$.");
		String vhdlFilePath=kiss2Path.toString().split("-\\$fsm")[0];	
		String sourceFileName=vhdlFilePath.substring(vhdlFilePath.lastIndexOf("/"))+".vhd";
		
		Optional<Path> oPath = Optional.empty();
		try {
			oPath = Files.walk(Paths.get(baseProjDir)).filter(Files::isRegularFile).filter(o->o.toString().toLowerCase().endsWith(sourceFileName)||o.toString().toLowerCase().endsWith(sourceFileName+"l")).findFirst();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		InputFile inputFile=null;
		File file=null;
		if(oPath.isPresent()) {
			inputFile = context.fileSystem().inputFile(predicates.hasPath(oPath.get().toString()));
			file = new File(oPath.get().toString());
		}
		
		if (inputFile!=null) {
			String stateName=kiss2FileName[1].split("\\$")[0];			
			String stateType="";
			int sigDecLine=1;
			try (FileReader fReader = new FileReader(file)){
				BufferedReader bufRead = new BufferedReader(fReader);
				String currentLine = null;
				int lineNumber=0;
				boolean foundStateType=false;
				while ((currentLine = bufRead.readLine()) != null && !foundStateType) {    												
					lineNumber++;
					Scanner input = new Scanner(currentLine);
					boolean sigDec=false;
					boolean sigType=false;
					while(input.hasNext()&&!foundStateType) {
						String currentToken = input.next();
						if (currentToken.equalsIgnoreCase("signal"))
							sigDec=true;
						else if(sigDec&&currentToken.equalsIgnoreCase(stateName))
							sigDecLine=lineNumber;								
						else if(sigDec&&currentToken.equalsIgnoreCase(":")) {
							sigDec=false;
							sigType=true;
						}
						else if(sigType) {
							stateType=currentToken.toLowerCase();
							foundStateType=true;
						}
					}
					input.close();
				}
			} catch (IOException e) {
				LOG.warn("Could not read source file");
			}


			if(fsmRegex!=null && !stateName.matches(fsmRegex)) 
				addNewIssue("CNE_02000",inputFile,sigDecLine,"State machine signal "+stateName+" is miswritten.");				
			if(context.activeRules().findByInternalKey(repo, "STD_03900")!=null && (stateType.startsWith("std_")||(stateType.startsWith("ieee_"))))
				addNewIssue("STD_03900",inputFile,sigDecLine,"State machine signal "+stateName+" uses wrong type.");

		}

		kiss2Path.toFile().deleteOnExit();		
	}

	private void addNewIssue(String ruleId, InputFile inputFile, int line, String msg) {
		NewIssue ni = context.newIssue()
				.forRule(RuleKey.of(repo,ruleId));
		NewIssueLocation issueLocation = ni.newLocation()
				.on(inputFile)
				.at(inputFile.selectLine(line))
				.message(msg);
		ni.at(issueLocation);
		ni.save(); 
	}

	@VisibleForTesting
	protected void importReport(Path reportFile, SensorContext context) {
		try {
			LOG.info("Importing {}", reportFile.getFileName());
			boolean rcSynth=reportFile.toString().startsWith(RC_SYNTH_REPORT_PATH);
			for(Issue issue : ReportXmlParser.getIssues(reportFile)){
				try {
					importIssue(context, issue,rcSynth);
				} catch (RuntimeException e) {
					LOG.warn("Can't import an issue from report {} : {}", reportFile.getFileName(), e.getMessage());
				}  
			}
		} catch (XMLStreamException e) {			
			LOG.error("Error when reading xml report : {}", e.getLocation());
		}  
	}

	private void importIssue(SensorContext context, Issue i, boolean reportFromRcsynth) {
		InputFile inputFile;
		NewIssueLocation issueLocation;
		Path p = i.file();
		Path filePath;
		if (reportFromRcsynth)
			filePath=p;
		else { 
			Path root = Paths.get("./");
			filePath = root.resolve(p.subpath(2, p.getNameCount()));//Zamia adds "./vhdl" to inputFile path in reports
		}
		//FilePredicates predicates = context.fileSystem().predicates();
		inputFile = context.fileSystem().inputFile(predicates.hasPath(filePath.toString()));
		if(inputFile == null) {
			if(!unfoundFiles.contains(filePath.toString())){    
				unfoundFiles.add(filePath.toString());
			}
		} else {
			NewIssue ni = context.newIssue()
					.forRule(RuleKey.of(repo,i.ruleKey()));
			issueLocation = ni.newLocation()
					.on(inputFile)
					.at(inputFile.selectLine(i.line()))
					.message(i.errorMsg());
			ni.at(issueLocation);
			ni.save(); 
		}
	}

	public String executeCommand(String[] cmd) {
		StringBuffer theRun = new StringBuffer();
		try {
			Process process = Runtime.getRuntime().exec(cmd);

			BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream()));
			int read;
			char[] buffer = new char[4096];
			StringBuffer output = new StringBuffer();
			while ((read = reader.read(buffer)) > 0) {
				theRun = output.append(buffer, 0, read);
			}
			reader.close();
			process.waitFor();

		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			LOG.warn("Command thread interrupted");
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
		return theRun.toString().trim();
	}

}
