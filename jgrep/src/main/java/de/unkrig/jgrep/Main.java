
/*
 * jgrep - An advanced Java search tool
 *
 * Copyright (c) 2018 Arno Unkrig. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *       following disclaimer.
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *       following disclaimer in the documentation and/or other materials provided with the distribution.
 *    3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package de.unkrig.jgrep;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.ErrorHandler;
import org.codehaus.commons.compiler.Location;
import org.codehaus.commons.nullanalysis.NotNullByDefault;
import org.codehaus.commons.nullanalysis.Nullable;
import org.codehaus.janino.Descriptor;
import org.codehaus.janino.IClass;
import org.codehaus.janino.IClassLoader;
import org.codehaus.janino.Java;
import org.codehaus.janino.Java.CompilationUnit;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.ScriptEvaluator;
import org.codehaus.janino.UnitCompiler;
import org.codehaus.janino.util.AbstractTraverser;
import org.codehaus.janino.util.StringPattern;
import org.codehaus.janino.util.Traverser;
import org.codehaus.janino.util.iterator.DirectoryIterator;
import org.codehaus.janino.util.resource.PathResourceFinder;

/**
 * @see #main(String[])
 */
public // SUPPRESS CHECKSTYLE HideUtilityClassConstructor
class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    
    // Tool configuration.

    private StringPattern[] directoryNamePatterns     = StringPattern.PATTERNS_ALL;
    private StringPattern[] fileNamePatterns          = { new StringPattern("*.java") };
    private File[]          classPath                 = { new File(".") };
    private File[]          optionalExtDirs           = null;
    private File[]          optionalBootClassPath     = null;
    private String          optionalCharacterEncoding = null;

    /**
     * Reads a set of compilation units from the file system and searches it for specific Java elements, e.g.
     * invocations of a particular method.
     * <p>
     *   Usage:
     * </p>
     * <pre>
     *   java de.unkrig.jgrep.Main \
     *       [ -dirs <var>directory-name-patterns</var> ] \
     *       [ -files <var>file-name-patterns</var> ] \
     *       { <var>root-directory</var> } \
     *       { <var>action</var> }
     * </pre>
     * <p>
     *   If {@code -dirs" is not given, then all subdirectories of the <var>root-directory</var>s are scanned for
     *   source files. The <var>directory-name-patterns</var> work as described in {@link
     *   org.codehaus.janino.util.StringPattern#parseCombinedPattern(String)}.
     * </p>
     * <p>
     *   If {@code -files} is not given, then all files ending in ".java" are read. The <var>file-name-patterns</var>
     *   work as described in {@link org.codehaus.janino.util.StringPattern#parseCombinedPattern(String)}.
     * </p>
     * <p>
     *   The <var>action</var>s determine what happens with each Java element in the compilation units. Each
     *   <var>script</var> has a single parameter named "_" (underscore) which has the designated type.
     * </p>
     * <p>
     *   Action example:
     * </p>
     * <pre>
     *   -fieldDeclaration "if (Mod.isStatic(_.modifiers.accessFlags)) printf(\"%-100s %s%n\", _.getLocation(), _);"
     * </pre>
     * <p>
     *   Possible actions are:
     * </p>
     * <dl>
     *   <dt>-compilationUnit <em>script</em></dt>                        <dd>See {@link CompilationUnit}</dd>
     *   <dt>-singleTypeImportDeclaration <em>script</em></dt>            <dd>See {@link SingleTypeImportDeclaration}</dd>
     *   <dt>-typeImportOnDemandDeclaration <em>script</em></dt>          <dd>See {@link TypeImportOnDemandDeclaration}</dd>
     *   <dt>-singleStaticImportDeclaration <em>script</em></dt>          <dd>See {@link SingleStaticImportDeclaration}</dd>
     *   <dt>-staticImportOnDemandDeclaration <em>script</em></dt>        <dd>See {@link StaticImportOnDemandDeclaration}</dd>
     *   <dt>-importDeclaration <em>script</em></dt>                      <dd>See {@link ImportDeclaration}</dd>
     *   <dt>-anonymousClassDeclaration <em>script</em></dt>              <dd>See {@link AnonymousClassDeclaration}</dd>
     *   <dt>-localClassDeclaration <em>script</em></dt>                  <dd>See {@link LocalClassDeclaration}</dd>
     *   <dt>-packageMemberClassDeclaration <em>script</em></dt>          <dd>See {@link AbstractPackageMemberClassDeclaration}</dd>
     *   <dt>-memberInterfaceDeclaration <em>script</em></dt>             <dd>See {@link MemberInterfaceDeclaration}</dd>
     *   <dt>-packageMemberInterfaceDeclaration <em>script</em></dt>      <dd>See {@link PackageMemberInterfaceDeclaration}</dd>
     *   <dt>-memberClassDeclaration <em>script</em></dt>                 <dd>See {@link MemberClassDeclaration}</dd>
     *   <dt>-constructorDeclarator <em>script</em></dt>                  <dd>See {@link ConstructorDeclarator}</dd>
     *   <dt>-initializer <em>script</em></dt>                            <dd>See {@link Initializer}</dd>
     *   <dt>-methodDeclarator <em>script</em></dt>                       <dd>See {@link MethodDeclarator}</dd>
     *   <dt>-fieldDeclaration <em>script</em></dt>                       <dd>See {@link FieldDeclaration}</dd>
     *   <dt>-labeledStatement <em>script</em></dt>                       <dd>See {@link LabeledStatement}</dd>
     *   <dt>-block <em>script</em></dt>                                  <dd>See {@link Block}</dd>
     *   <dt>-expressionStatement <em>script</em></dt>                    <dd>See {@link ExpressionStatement}</dd>
     *   <dt>-ifStatement <em>script</em></dt>                            <dd>See {@link IfStatement}</dd>
     *   <dt>-forStatement <em>script</em></dt>                           <dd>See {@link ForStatement}</dd>
     *   <dt>-forEachStatement <em>script</em></dt>                       <dd>See {@link ForEachStatement}</dd>
     *   <dt>-whileStatement <em>script</em></dt>                         <dd>See {@link WhileStatement}</dd>
     *   <dt>-tryStatement <em>script</em></dt>                           <dd>See {@link TryStatement}</dd>
     *   <dt>-switchStatement <em>script</em></dt>                        <dd>See {@link SwitchStatement}</dd>
     *   <dt>-synchronizedStatement <em>script</em></dt>                  <dd>See {@link SynchronizedStatement}</dd>
     *   <dt>-doStatement <em>script</em></dt>                            <dd>See {@link DoStatement}</dd>
     *   <dt>-localVariableDeclarationStatement <em>script</em></dt>      <dd>See {@link LocalVariableDeclarationStatement}</dd>
     *   <dt>-returnStatement <em>script</em></dt>                        <dd>See {@link ReturnStatement}</dd>
     *   <dt>-throwStatement <em>script</em></dt>                         <dd>See {@link ThrowStatement}</dd>
     *   <dt>-breakStatement <em>script</em></dt>                         <dd>See {@link BreakStatement}</dd>
     *   <dt>-continueStatement <em>script</em></dt>                      <dd>See {@link ContinueStatement}</dd>
     *   <dt>-assertStatement <em>script</em></dt>                        <dd>See {@link AssertStatement}</dd>
     *   <dt>-emptyStatement <em>script</em></dt>                         <dd>See {@link EmptyStatement}</dd>
     *   <dt>-localClassDeclarationStatement <em>script</em></dt>         <dd>See {@link LocalClassDeclarationStatement}</dd>
     *   <dt>-package <em>script</em></dt>                                <dd>See {@link Package}</dd>
     *   <dt>-arrayLength <em>script</em></dt>                            <dd>See {@link ArrayLength}</dd>
     *   <dt>-assignment <em>script</em></dt>                             <dd>See {@link Assignment}</dd>
     *   <dt>-unaryOperation <em>script</em></dt>                         <dd>See {@link UnaryOperation}</dd>
     *   <dt>-binaryOperation <em>script</em></dt>                        <dd>See {@link BinaryOperation}</dd>
     *   <dt>-cast</dt> <em>script</em></dt>                              <dd>See {@link Cast}</dd>
     *   <dt>-classLiteral <em>script</em></dt>                           <dd>See {@link ClassLiteral}</dd>
     *   <dt>-conditionalExpression <em>script</em></dt>                  <dd>See {@link ConditionalExpression}</dd>
     *   <dt>-crement <em>script</em></dt>                                <dd>See {@link Crement}</dd>
     *   <dt>-instanceof <em>script</em></dt>                             <dd>See {@link Instanceof}</dd>
     *   <dt>-methodInvocation <em>script</em></dt>                       <dd>See {@link MethodInvocation}</dd>
     *   <dt>-superclassMethodInvocation <em>script</em></dt>             <dd>See {@link SuperclassMethodInvocation}</dd>
     *   <dt>-literal <em>script</em></dt>                                <dd>See {@link Literal}</dd>
     *   <dt>-integerLiteral <em>script</em></dt>                         <dd>See {@link IntegerLiteral}</dd>
     *   <dt>-floatingPointLiteral <em>script</em></dt>                   <dd>See {@link FloatingPointLiteral}</dd>
     *   <dt>-booleanLiteral <em>script</em></dt>                         <dd>See {@link BooleanLiteral}</dd>
     *   <dt>-characterLiteral <em>script</em></dt>                       <dd>See {@link CharacterLiteral}</dd>
     *   <dt>-stringLiteral <em>script</em></dt>                          <dd>See {@link StringLiteral}</dd>
     *   <dt>-nullLiteral <em>script</em></dt>                            <dd>See {@link NullLiteral}</dd>
     *   <dt>-simpleLiteral <em>script</em></dt>                          <dd>See {@link SimpleConstant}</dd>
     *   <dt>-newAnonymousClassInstance <em>script</em></dt>              <dd>See {@link NewAnonymousClassInstance}</dd>
     *   <dt>-newArray <em>script</em></dt>                               <dd>See {@link NewArray}</dd>
     *   <dt>-newInitializedArray <em>script</em></dt>                    <dd>See {@link NewInitializedArray}</dd>
     *   <dt>-arrayInitializerOrRvalue <em>script</em></dt>               <dd>See {@link ArrayInitializerOrRvalue}</dd>
     *   <dt>-newClassInstance <em>script</em></dt>                       <dd>See {@link NewClassInstance}</dd>
     *   <dt>-parameterAccess <em>script</em></dt>                        <dd>See {@link ParameterAccess}</dd>
     *   <dt>-qualifiedThisReference <em>script</em></dt>                 <dd>See {@link QualifiedThisReference}</dd>
     *   <dt>-thisReference <em>script</em></dt>                          <dd>See {@link ThisReference}</dd>
     *   <dt>-arrayType <em>script</em></dt>                              <dd>See {@link ArrayType}</dd>
     *   <dt>-primitiveType <em>script</em></dt>                          <dd>See {@link PrimitiveType}</dd>
     *   <dt>-referenceType <em>script</em></dt>                          <dd>See {@link ReferenceType}</dd>
     *   <dt>-rvalueMemberType <em>script</em></dt>                       <dd>See {@link RvalueMemberType}</dd>
     *   <dt>-simpleType <em>script</em></dt>                             <dd>See {@link SimpleType}</dd>
     *   <dt>-alternateConstructorInvocation <em>script</em></dt>         <dd>See {@link AlternateConstructorInvocation}</dd>
     *   <dt>-superConstructorInvocation <em>script</em></dt>             <dd>See {@link SuperConstructorInvocation}</dd>
     *   <dt>-ambiguousName <em>script</em></dt>                          <dd>See {@link AmbiguousName}</dd>
     *   <dt>-arrayAccessExpression <em>script</em></dt>                  <dd>See {@link ArrayAccessExpression}</dd>
     *   <dt>-fieldAccess <em>script</em></dt>                            <dd>See {@link FieldAccess}</dd>
     *   <dt>-fieldAccessExpression <em>script</em></dt>                  <dd>See {@link FieldAccessExpression}</dd>
     *   <dt>-superclassFieldAccessExpression <em>script</em></dt>        <dd>See {@link SuperclassFieldAccessExpression}</dd>
     *   <dt>-localVariableAccess <em>script</em></dt>                    <dd>See {@link LocalVariableAccess}</dd>
     *   <dt>-parenthesizedExpression <em>script</em></dt>                <dd>See {@link ParenthesizedExpression}</dd>
     *   <dt>-elementValueArrayInitializer <em>script</em></dt>           <dd>See {@link ElementValueArrayInitializer}</dd>
     *   <dt>-elementValue <em>script</em></dt>                           <dd>See {@link ElementValue}</dd>
     *   <dt>-singleElementAnnotation <em>script</em></dt>                <dd>See {@link SingleElementAnnotation}</dd>
     *   <dt>-annotation <em>script</em></dt>                             <dd>See {@link Annotation}</dd>
     *   <dt>-normalAnnotation <em>script</em></dt>                       <dd>See {@link NormalAnnotation}</dd>
     *   <dt>-markerAnnotation <em>script</em></dt>                       <dd>See {@link MarkerAnnotation}</dd>
     *   <dt>-classDeclaration <em>script</em></dt>                       <dd>See {@link AbstractClassDeclaration}</dd>
     *   <dt>-abstractTypeDeclaration <em>script</em></dt>                <dd>See {@link AbstractTypeDeclaration}</dd>
     *   <dt>-namedClassDeclaration <em>script</em></dt>                  <dd>See {@link NamedClassDeclaration}</dd>
     *   <dt>-interfaceDeclaration <em>script</em></dt>                   <dd>See {@link InterfaceDeclaration}</dd>
     *   <dt>-functionDeclarator <em>script</em></dt>                     <dd>See {@link FunctionDeclarator}</dd>
     *   <dt>-formalParameters <em>script</em></dt>                       <dd>See {@link FormalParameters}</dd>
     *   <dt>-formalParameter <em>script</em></dt>                        <dd>See {@link FormalParameter}</dd>
     *   <dt>-abstractTypeBodyDeclaration <em>script</em></dt>            <dd>See {@link AbstractTypeBodyDeclaration}</dd>
     *   <dt>-statement <em>script</em></dt>                              <dd>See {@link Statement}</dd>
     *   <dt>-breakableStatement <em>script</em></dt>                     <dd>See {@link BreakableStatement}</dd>
     *   <dt>-continuableStatement <em>script</em></dt>                   <dd>See {@link ContinuableStatement}</dd>
     *   <dt>-rvalue <em>script</em></dt>                                 <dd>See {@link Rvalue}</dd>
     *   <dt>-booleanRvalue <em>script</em></dt>                          <dd>See {@link BooleanRvalue}</dd>
     *   <dt>-invocation <em>script</em></dt>                             <dd>See {@link Invocation}</dd>
     *   <dt>-constructorInvocation <em>script</em></dt>                  <dd>See {@link ConstructorInvocation}</dd>
     *   <dt>-enumConstant <em>script</em></dt>                           <dd>See {@link EnumConstant}</dd>
     *   <dt>-packageMemberEnumDeclaration <em>script</em></dt>           <dd>See {@link PackageMemberEnumDeclaration}</dd>
     *   <dt>-memberEnumDeclaration <em>script</em></dt>                  <dd>See {@link MemberEnumDeclaration}</dd>
     *   <dt>-packageMemberAnnotationTypeDeclaration <em>script</em></dt> <dd>See {@link PackageMemberAnnotationTypeDeclaration}</dd>
     *   <dt>-memberAnnotationTypeDeclaration <em>script</em></dt>        <dd>See {@link MemberAnnotationTypeDeclaration}</dd>
     *   <dt>-lvalue <em>script</em></dt>                                 <dd>See {@link Lvalue}</dd>
     *   <dt>-type <em>script</em></dt>                                   <dd>See {@link Type}</dd>
     *   <dt>-atom <em>script</em></dt>                                   <dd>See {@link Atom}</dd>
     *   <dt>-located <em>script</em></dt>                                <dd>See {@link Located}</dd>
     * </dl>
     */
    public static void
    main(String[] args) {
    	
    	try {
    		main2(args);
        } catch (Exception e) {
            System.err.println(e.toString());
            System.exit(1);
        }
    }

    /**
     * Base class for the action scripts; provides shorthands for various JRE methods.
     */
    public static
    class Util {
    	public static void println()                             { System.out.println(); }
    	public static void println(String x)                     { System.out.println(x); }
    	public static void println(Object x)                     { System.out.println(x); }
    	public static void printf(String format, Object... args) { System.out.printf(format, args); }
    }
    
	private static void
	main2(String[] args) throws CompileException, IOException {

		int argi = 0;
        
        // Create the MAIN object.
        final Main main = new Main();

        // Configure the MAIN object.
        for (; argi < args.length; ++argi) {

            String arg = args[argi];
            if (!arg.startsWith("-")) break;

            switch (arg) {

            case "-dirs":
                main.directoryNamePatterns = StringPattern.parseCombinedPattern(args[++argi]);
                break;

            case "-files":
                main.fileNamePatterns = StringPattern.parseCombinedPattern(args[++argi]);
                break;

            case "-classpath":
                main.classPath = PathResourceFinder.parsePath(args[++argi]);
                break;

            case "-extdirs":
                main.optionalExtDirs = PathResourceFinder.parsePath(args[++argi]);
                break;

            case "-bootclasspath":
                main.optionalBootClassPath = PathResourceFinder.parsePath(args[++argi]);
                break;

            case "-encoding":
                main.optionalCharacterEncoding = args[++argi];
                break;

            case "-help":
                for (String s : Main.USAGE) System.out.println(s);
                System.exit(1);
                return; /* NEVER REACHED */

            default:
                System.err.println("Unexpected command-line argument \"" + arg + "\", try \"-help\".");
                System.exit(1);
                return; /* NEVER REACHED */
            }
        }

        // { directory-path }
        File[] rootDirectories;
        {
            int first = argi;
            for (; argi < args.length && !args[argi].startsWith("-"); ++argi);
            if (argi == first) {
                System.err.println("No <directory-path>es given, try \"-help\".");
                System.exit(1);
                return; /* NEVER REACHED */
            }
            rootDirectories = new File[argi - first];
            for (int i = first; i < argi; ++i) rootDirectories[i - first] = new File(args[i]);
        }

        // The remaining command line args are the "actions", e.h. "-fieldDeclaration System.oupt.println(_);".
        final Map<Method, List<ScriptEvaluator>> scripts = new HashMap<>();
        while (argi < args.length) {

        	String arg = args[argi++];
        	if (!arg.startsWith("-")) {
        		System.err.println("Invalid action \"" + arg + "\"; try \"-help\"");
        		System.exit(1);
        	}

        	// Actions must match the "traverse...()" methods of "Traverser":
            Method m;
            METHODS: {
            	for (Method m2 : Traverser.class.getMethods()) {
	            	if (m2.getName().toLowerCase().equals("traverse" + arg.substring(1).toLowerCase())) {
	            		m = m2;
	            		break METHODS;
	            	}
            	}
	            	
            	System.err.println("Invalid action \"" + arg + "\"; try \"-help\"");
            	System.exit(1);
            	return;
            }

            // Scan, parse and compile the script of the action.
            List<ScriptEvaluator> ses = scripts.get(m);
            if (ses == null) scripts.put(m, (ses = new ArrayList<>()));
	            		
    		ScriptEvaluator se = new ScriptEvaluator();
    		se.setParameters(new String[] { "_" }, new Class[] { m.getParameterTypes()[0] });
    		se.setDefaultImports("org.codehaus.janino.*");
    		se.setExtendedClass(Util.class);
    		
    		se.cook(args[argi++]);
    		
    		ses.add(se);
        }

        // JGrep the root directories.
        main.main3(rootDirectories, scripts);
    }

    private static final String[] USAGE = {
        "Usage:",
        "",
        "  java org.codehaus.janino.tools.JGrep [ <option> ... ] <root-dir> ... <pattern> ...",
        "  java org.codehaus.janino.tools.JGrep -help",
        "",
        "Reads a set of compilation units from the files in the <root-dir>s and their",
        "subdirectories and searches them for specific Java[TM] constructs, e.g.",
        "invocations of a particular method.",
        "",
        "Supported <option>s are ('cp' is a 'combined pattern, like '*.java-*Generated*'):",
        "  -dirs <dir-cp>             Ignore subdirectories which don't match",
        "  -files <file-cp>           Include only matching files (default is '*.java')",
        "  -classpath <classpath>",
        "  -extdirs <classpath>",
        "  -bootclasspath <classpath>",
        "  -encoding <encoding>",
        "  -verbose",
        "",
        "Supported <pattern>s are:",
        "  -method-invocation <method-pattern> [ predicate:<predicate-expression> | action:<action-script> ] ...",
        "<method-pattern> is ('<ip>' is an 'identifier pattern' like '*foo*'):",
        "  -method-invocation <method-ip>",
        "  -method-invocation <simple-class-ip>.<method-ip>",
        "  -method-invocation <fully-qualified-class-ip>.<method-ip>",
        "  -method-invocation <method-ip>([<parameter-ip>[,<parameter-ip>]...])",
        "  -field-declaration { [!]<modifier> } [<simple-class-ip>.|<fully-qualified-class-ip>.]<field-ip>",
        "",
        "<predicate-expression> is a Java[TM] expression with the following signature:",
        "  boolean evaluate(UnitCompiler uc, Java.Invocation invocation, IClass.IMethod method)",
        "",
        "<action-script> is either",
        "  print-location-and-match",
        "  print-location",
        ", or a Java[TM] script (method body) with the following signature:",
        "  void execute(UnitCompiler uc, Java.Invocation invocation, IClass.IMethod method)",
    };

    private void
    main3(File[] rootDirectories, Map<Method, List<ScriptEvaluator>> scripts) throws CompileException, IOException {

        this.main4(
    		DirectoryIterator.traverseDirectories(
	            rootDirectories,              // rootDirectories
	            new FilenameFilter() {        // directoryNameFilter
	                @NotNullByDefault(false) @Override public boolean
	                accept(@Nullable File dir, @Nullable String name) {
	                    return StringPattern.matches(directoryNamePatterns, name);
	                }
	            },
	            new FilenameFilter() {        // fileNameFilter
	            	@NotNullByDefault(false) @Override public boolean
	            	accept(@Nullable File dir, @Nullable String name) {
	            		return StringPattern.matches(fileNamePatterns, name);
	        		}
	            }
	        ),
    		scripts
		);
    }

    private  void
    main4(
        Iterator<File>                     sourceFilesIterator,
        Map<Method, List<ScriptEvaluator>> scripts
    ) throws CompileException, IOException {

    	// Set up an IClassLoader that reads IClasses from the configured directories.
    	List<UnitCompiler> parsedCompilationUnits = new ArrayList<UnitCompiler>();
    	IClassLoader iClassLoader = new JGrepIClassLoader(
			IClassLoader.createJavacLikePathIClassLoader(
	            this.optionalBootClassPath,
	            this.optionalExtDirs,
	            this.classPath
	        ),
			parsedCompilationUnits
		);

    	// Now parse all source files.
        while (sourceFilesIterator.hasNext()) {
            File sourceFile = (File) sourceFilesIterator.next();
            
            UnitCompiler uc = new UnitCompiler(
        		this.parseCompilationUnit(sourceFile, this.optionalCharacterEncoding),
        		iClassLoader
    		);
            uc.setCompileErrorHandler(new ErrorHandler() {

                @Override public void
                handleError(String message, @Nullable Location optionalLocation) {
                    System.out.printf(
                        "%s: %s%n",
                        optionalLocation == null ? "???" : optionalLocation.toString(),
                        message
                    );
                }
            });

            parsedCompilationUnits.add(uc);
        }

        // Traverse the parsed compilation units.
        for (final UnitCompiler unitCompiler : parsedCompilationUnits) {

            CompilationUnit compilationUnit = unitCompiler.getCompilationUnit();
            
        	@SuppressWarnings("unchecked") final Traverser<RuntimeException>[]
			delegate = new Traverser[1];
        	
        	@SuppressWarnings("unchecked") Traverser<RuntimeException>
        	traverserProxy = (Traverser<RuntimeException>) Proxy.newProxyInstance(
    			this.getClass().getClassLoader(),
    			new Class<?>[] { Traverser.class },
    			new InvocationHandler() {
					
					@Override public Object
					invoke(Object proxy, Method method, Object[] args) throws Throwable {
						
						// Are any actions defined for this Java element?
						List<ScriptEvaluator> ses = scripts.get(method);
						if (ses != null) {
							for (ScriptEvaluator se : ses) se.evaluate(args);
						}
						
						// Delegate to superclass method.
						return method.invoke(delegate[0], args);
					}
				}
			);
        	delegate[0] = new AbstractTraverser<>(traverserProxy);
			traverserProxy.traverseCompilationUnit(compilationUnit);
        }
    }

    /**
     * Opens, scans and parses one compilation unit from a file.
     *
     * @return the parsed compilation unit
     */
    private Java.CompilationUnit
    parseCompilationUnit(File sourceFile, @Nullable String optionalCharacterEncoding)
    throws CompileException, IOException {
    	
    	try (InputStream is = new BufferedInputStream(new FileInputStream(sourceFile))) {
            return new Parser(new Scanner(sourceFile.getPath(), is, optionalCharacterEncoding)).parseCompilationUnit();
        }
    }

    /**
     * A specialized {@link IClassLoader} that loads {@link IClass}es from the following sources:
     * <ol>
     *   <li>The parent class loader
     *   <li>An already-parsed compilation unit
     * </ol>
     */
    private static
    class JGrepIClassLoader extends IClassLoader {

        private final List<UnitCompiler> parsedCompilationUnits;

		/**
         * @param optionalParentIClassLoader The {@link IClassLoader} through which {@link IClass}es are to be loaded
         * @param parsedCompilationUnits     The set of already-parsed which can be re-used
         */
        JGrepIClassLoader(
    		@Nullable IClassLoader optionalParentIClassLoader,
    		List<UnitCompiler> parsedCompilationUnits
		) {
            super(optionalParentIClassLoader);
			this.parsedCompilationUnits = parsedCompilationUnits;
            super.postConstruct();
        }

        /**
         * @param type Field descriptor of the {@IClass} to load, e.g. "Lpkg1/pkg2/Outer$Inner;"
         */
        @Override @Nullable protected IClass
        findIClass(final String type) {
            Main.LOGGER.entering(null, "findIClass", type);

            // Class type.
            String className = Descriptor.toClassName(type); // E.g. "pkg1.pkg2.Outer$Inner"
            Main.LOGGER.log(Level.FINE, "className={0}", className);

            // Do not attempt to load classes from package "java".
            if (className.startsWith("java.")) return null;

            // Check the already-parsed compilation units.
            for (int i = 0; i < this.parsedCompilationUnits.size(); ++i) {
                UnitCompiler uc  = this.parsedCompilationUnits.get(i);
                IClass       res = uc.findClass(className);
                if (res != null) {
                    this.defineIClass(res);
                    return res;
                }
            }
            return null;
        }
    }
}
