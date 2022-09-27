import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.github.javaparser.utils.SourceRoot;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.javaparser.StaticJavaParser.parseStatement;

public class ContractDiscovery {

    private static String path;
    private static TypeSolver typeSolver = null;
    private static final TypeSolver reflection = new ReflectionTypeSolver();
    private static final TypeSolver primitiveSolver = new MemoryTypeSolver();

    private static Integer reWriteClass;
    private static String pomPath;

    public static void main(String[] args) throws Exception {

        SelectProjectsPath();

        Path ROOT_PATH = Paths.get(path);
        JavaSymbolSolver symbolSolver = SetupSimbolSolver();
        SourceRoot sr = new SourceRoot(ROOT_PATH, new ParserConfiguration().setSymbolResolver(symbolSolver));
        List<ParseResult<CompilationUnit>> all_cu;
        try {
            all_cu = sr.tryToParseParallelized();
        } catch (Exception e) {
            System.out.println(e);
            return;
        }

        System.out.println(path);
        PomInject pomInjector = new PomInject(pomPath);
        pomInjector.addFailSafeDep();
        pomInjector.printAllDeps();
        pomInjector.OverWritePom();

        final List<String> interfaceToLookup = new ArrayList<String>();
        LookupForRemoteInterface(all_cu, interfaceToLookup);
        System.out.println("FOUND REMOTE INTERFACE " + interfaceToLookup.stream().findFirst().toString());


        /*
         * For each cu class it wiill search for assignment expressions and cast expressions that indicates a reference to an intance of the concrete Remote class.
         */
        all_cu.forEach(cu -> {
            reWriteClass = 0;
            List<Integer> lines = new ArrayList<Integer>();
            Optional<CompilationUnit> localCu = cu.getResult();

            if (!localCu.isPresent())
                return;
            AddImportDeclaration(localCu);


            //search for assignment exressions
            List<Expression> exps = localCu.get().findAll(Expression.class).stream()
                    .filter(ae -> ae.isCastExpr() || ae.isAssignExpr())
                    .collect(Collectors.toList());

            HashMap<Expression, Boolean> validExp = new HashMap<Expression, Boolean>();
            for (Expression exp : exps)
                validExp.put(exp, Boolean.TRUE);


                //check if the expression represent a reference to the remote interface.
            for (Expression exp : exps) {
                if (validExp.get(exp) == Boolean.FALSE)
                    continue;
                // remove the related common expression cause an assignment expression could be
                // seen two times.
                if (CheckForCommonExpression(exps, exp, validExp))
                    continue;
                // continue with the remaining logic
                String expType;
                try {
                    expType = exp.calculateResolvedType().asReferenceType().getQualifiedName();
                } catch (Exception e) {
                    expType = "dummySymbol";
                    System.out.println("NOT PARSING CLASS");
                }

                //if the found type is the one that we are looking for
                if (interfaceToLookup.contains(expType)) {
                    System.out.println(exp.toString());
                    if (exp.isAssignExpr()) {
                        AssignExpr ae = (AssignExpr) exp;
                        Expression target = ae.getTarget();
                        System.out.println("IS ASSIGN EXPR: " + target);
                        GetLinesUsage(lines, localCu, target);
                    }
                    // getting all types in processed CU
                    List<TypeDeclaration<?>> typeDeclarations = localCu.get().getTypes();
                    if (!lines.isEmpty()) {
                        reWriteClass = 1;
                        System.out.println("My lines:" + lines);
                        InnestCode(lines, typeDeclarations);
                    }
                }
            }
            ;
            if (reWriteClass == 1) {
                try {
                    reWriteClass(localCu.get());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private static void SelectProjectsPath() {
        FileNameExtensionFilter xmlfilter = new FileNameExtensionFilter(
                        "xml files (*.xml)", "xml");


        Runnable openF1 = new Runnable() {

            @Override
            public void run() {

                JFileChooser f = new JFileChooser();
            
                f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                f.setDialogTitle("Open project FOLDER");
                f.showSaveDialog(null);

                path = f.getCurrentDirectory().toString();
                System.out.println("SELECTED:" + path);

                String lastPath = path.substring(path.lastIndexOf('/') + 1);
                if (!lastPath.equals("java")) {
                    System.out.println("project path is wrong, make sure that your last folder is /java");
                    System.exit(1);
                }

                typeSolver = new JavaParserTypeSolver(new File(path));
            }
        };

    


        Runnable openF2 = new Runnable() {

            @Override
            public void run() {

                JFileChooser f2 = new JFileChooser();

                f2.setDialogTitle("Open pom file");
                f2.setFileFilter(xmlfilter);
                f2.showOpenDialog(null);
                f2.setFileSelectionMode(JFileChooser.FILES_ONLY);
        
                pomPath = f2.getSelectedFile().getAbsolutePath();
                System.out.println(pomPath);
                
            }
        };

        try{
            SwingUtilities.invokeAndWait(openF1);

            SwingUtilities.invokeAndWait(openF2);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    
    }

    private static void AddImportDeclaration(Optional<CompilationUnit> localCu) {
        localCu.get().addImport(new ImportDeclaration("dev.failsafe.Failsafe", false, false));
        localCu.get().addImport(new ImportDeclaration("dev.failsafe.RetryPolicy", false, false));
        localCu.get().addImport(new ImportDeclaration("java.time.temporal.ChronoUnit", false, false));
    }

    /*
     * create a copy of the entire class takn from the CompilationUnit if the user wants it.
     */
    private static void reWriteClass(CompilationUnit cu) throws IOException {

        String className = cu.findAll(ClassOrInterfaceDeclaration.class).stream().findFirst().get().getNameAsString();

        int dialogButton = JOptionPane.YES_NO_OPTION;
        int dialogResult = JOptionPane.showConfirmDialog(null, "Would You Like to overwrite: " + className + "?",
                "Warning", dialogButton);
        if (dialogResult == JOptionPane.NO_OPTION) {
            return;
        }

        File classFile = new File(cu.getStorage().get().getPath().toString()); // use it to overwrite the original ONE
        classFile.delete();
        // File classFile = new
        // File("/Users/thenor/Desktop/Aldo/ResilientParser/src/main/java/"+className+".java");
        classFile.createNewFile();
        System.out.println(classFile.getPath() + " created successfully...");
        FileWriter fr = null;
        try {
            fr = new FileWriter(classFile, false);
            fr.write(cu.toString());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            assert fr != null;
            fr.close();
        }
    }

    /*
     * Check if the expression is a valid one.
     */
    private static boolean CheckForCommonExpression(List<Expression> exps, Expression exp,
            HashMap<Expression, Boolean> validExp) {
        if (exp.isAssignExpr()) {
            AssignExpr ae = (AssignExpr) exp;
            int valueIndex = exps.indexOf(ae.getValue());
            if (valueIndex != -1)
                // exps.remove(valueIndex);
                validExp.replace(ae, Boolean.FALSE);
        } else { // common expression -> cast expression
            if (exps.stream().filter(e -> e.isAssignExpr()).map(e -> (AssignExpr) e)
                    .anyMatch(e -> e.getValue().equals(exp)))
                return true;
        }
        return false;
    }

    private static void GetLinesUsage(List<Integer> lines, Optional<CompilationUnit> localCu, Expression target) {
        localCu.get().findAll(MethodCallExpr.class).forEach(e -> {
            if (e.getScope().isPresent()) {
                if (e.getScope().get().equals(target)) {
                    System.out.println("need to be changed:" + e.toString());
                    lines.add(e.getRange().get().begin.line);
                }
            }
        });
    }

    /*
     * Scan all cu(s) with a discovery operation and choose only the ones that extend the "Remote" type.
     */
    private static void LookupForRemoteInterface(List<ParseResult<CompilationUnit>> all_cu,
            List<String> interfaceToLookup) {
        all_cu.forEach(cu -> {
            if (!cu.getResult().isPresent())
                return;
            cu.getResult().get().findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                NodeList<ClassOrInterfaceType> interfaceTypes = c.getExtendedTypes();
                if (interfaceTypes.size() == 0)
                    return;
                if (interfaceTypes.get(0).toString().contains("Remote")) {
                    Optional<String> qualifiedName = c.getFullyQualifiedName();
                    qualifiedName.ifPresent(s -> interfaceToLookup.add(s.toString()));
                }
            });
        });
    }


     /*
     * Setup the symbol solver creating a combined solver.
     */
    private static JavaSymbolSolver SetupSimbolSolver() {
        CombinedTypeSolver combinedSolver = new CombinedTypeSolver();
        combinedSolver.add(reflection);
        combinedSolver.add(typeSolver);

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedSolver);
        StaticJavaParser
                .getConfiguration()
                .setSymbolResolver(symbolSolver);

        return symbolSolver;
    }

    /*
     * innest the retry policy code to the specified lines
     * 
     */
    private static void InnestCode(List<Integer> lines, List<TypeDeclaration<?>> typeDeclarations) {
        System.out.println("\nINNEST CODE HERE");
        for (TypeDeclaration typeDec : typeDeclarations) {
            System.out.println("DISCOVERING class:" + typeDec.getName());

            NodeList<BodyDeclaration> members = typeDec.getMembers();
            Optional<BlockStmt> localBlock;
            for (BodyDeclaration member : members) {
                if (member instanceof ConstructorDeclaration) {
                    localBlock = Optional.ofNullable(((ConstructorDeclaration) member).getBody());
                } else if (member instanceof MethodDeclaration) {
                    localBlock = ((MethodDeclaration) member).getBody();
                } else
                    continue;

                if (localBlock.isPresent()) {
                    System.out.println("\tFound Block");
                    List<Integer> linesToRemove = new ArrayList<>();
                    InsertPolicy(lines, linesToRemove, localBlock);
                    lines.removeAll(linesToRemove);
                }

            }
        }
    }

    /*
     * iterate over each statement looking for a try catch one. Once it has been found, the retry policy became inserted.
     */
    private static List<Integer> InsertPolicy(List<Integer> lines, List<Integer> linesToRemove,
            Optional<BlockStmt> c) {
        BlockStmt body = c.get();
        body.ifBlockStmt((b) -> {
            NodeList<Statement> statements = b.getStatements();
            Iterator<Statement> iter = statements.iterator();
            HashMap<Integer, Integer> alreadyVisited = new HashMap<Integer, Integer>();
            while (iter.hasNext()) {
                Statement s = iter.next();
                if (s.isTryStmt()) {
                    int beginLine = s.getRange().get().begin.line;
                    int endLine = s.getRange().get().end.line;
                    for (Integer line : lines) {
                        System.out.println("\t\t\tAnalyzing line:" + line);
                        boolean lineIsInStatement = ((endLine >= line) && (line >= beginLine));
                        if (lineIsInStatement) {
                            System.out.println("\t\t\t\tINSERTING POLICY");
                            linesToRemove.add(line);
                            BlockStmt bs = s.findFirst(BlockStmt.class).get();
                            if (!alreadyVisited.containsKey(bs.getRange().get().begin.line)) {
                                AddRetryPolicy(alreadyVisited, bs);
                            }
                            bs.findAll(Statement.class).forEach(st -> {
                                if (st.getRange().get().begin.line == line) {
                                    AddRetryCode(bs, st);
                                }
                            });
                        } else
                            System.out.println("\t\t\t\tskipping...");
                    }
                } else
                    System.out.println("\t\tAnother STMT ");
            }
            ;
            PrintAllStatements(statements);
        });
        return linesToRemove;
    }

    /*
     * literally add the rerty policy declaration to the specified code block
     */
    private static void AddRetryPolicy(HashMap<Integer, Integer> alreadyVisited, BlockStmt bs) {
        bs.addStatement(parseStatement("RetryPolicy<Object> retryPolicy = RetryPolicy.builder()\n" +
                "    .handle(Exception.class)\n" +
                "    .withBackoff(1, 30, ChronoUnit.SECONDS)\n" +
                "    .withMaxRetries(3)\n" +
                "    .onRetriesExceeded(e -> System.out.println(\"Failed to connect. Max retries exceeded.\"))\n" +
                "    .build();"));
        alreadyVisited.put(bs.getRange().get().begin.line, bs.getRange().get().end.line);
    }

    /*
     * add the code that use the declared policy
     */
    private static void AddRetryCode(BlockStmt bs, Statement st) {
        st.remove();
        Expression localExpr = null;
        boolean isExp = st instanceof ExpressionStmt;
        boolean isReturnExp = st instanceof ReturnStmt;
        if (isExp)
            localExpr = ((ExpressionStmt) st).getExpression();
        else if (isReturnExp)
            localExpr = ((ReturnStmt) st).getExpression().get();

        if (isReturnExp)
            bs.addStatement(parseStatement("return Failsafe.with(retryPolicy).get(() ->" + localExpr + ");"));
        else
            bs.addStatement(parseStatement("Failsafe.with(retryPolicy).get(() ->" + localExpr + ");"));
    }


    /*
     * print all the specified statements
     */
    private static void PrintAllStatements(NodeList<Statement> statements) {
        System.out.println("\n\nSTATEMENTS");
        System.out.println("------");
        statements.forEach(s -> {
            System.out.println(s.toString());
        });
        System.out.println("------\n");
    }
}
