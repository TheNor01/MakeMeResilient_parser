import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;




public class PomInject {

    // /Users/thenor/Desktop/Aldo/ResilientParser/pom.xml";
    private String POM_PATH;
    public Model model;

    public PomInject(String PomPath) throws XmlPullParserException, IOException {
        this.POM_PATH=PomPath;
        this.model = getModel();

    }
    private Model getModel() throws IOException, XmlPullParserException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        this.model = reader.read(new FileReader(this.POM_PATH));
        return model;
    }

    public void printAllDeps(){
        System.out.println(model.getDependencies());
    }

    public void addFailSafeDep(){
        if(!this.model.getDependencies().stream().filter(dep-> dep.getArtifactId().equals("failsafe")).collect(Collectors.toList()).isEmpty()) return;
        Dependency failSafe = new Dependency();
        failSafe.setGroupId("dev.failsafe");
        failSafe.setArtifactId("failsafe");
        failSafe.setVersion("3.2.4");
        this.model.addDependency(failSafe);
        System.out.println("ADDED failsafe");
    }

    public void OverWritePom() {
        MavenXpp3Writer writer = new MavenXpp3Writer();
        try{
            writer.write(new FileWriter(this.POM_PATH), this.model);
        }catch (Exception e){
            System.out.println("e = " + e);
            return;
        }
    }

    public static void main(String [] args) throws IOException, XmlPullParserException {

        PomInject pomInjector = new PomInject("/Users/thenor/Desktop/Aldo/ResilientParser/pom.xml");
        pomInjector.addFailSafeDep();
        pomInjector.OverWritePom();

    }
}
