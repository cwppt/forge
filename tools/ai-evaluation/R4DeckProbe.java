import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.StaticData;
import forge.deck.*;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class R4DeckProbe {
  public static void main(String[] args) throws Exception {
    GuiBase.setInterface(new GuiDesktop());
    FModel.initialize(null,null);
    List<Object> results=new ArrayList<>();
    boolean pass=true;
    for(int i=1;i<args.length;i++) {
      File f=new File(args[i]); Deck d=DeckSerializer.fromFile(f);
      Map<String,Object> r=new LinkedHashMap<>();
      r.put("file",f.getAbsolutePath()); r.put("name",d.getName());
      r.put("main",d.getMain().countAll()); r.put("commanders",d.get(DeckSection.Commander).countAll());
      String problem=DeckFormat.Commander.getDeckConformanceProblem(d);
      r.put("commanderConformanceProblem",problem);
      Map<String,Integer> names=new TreeMap<>(); int creatures=0, dragons=0,lands=0;
      for (Map.Entry<PaperCard,Integer> e:d.getMain()) {
        PaperCard c=e.getKey(); int n=e.getValue(); names.merge(c.getName(),n,Integer::sum);
        if(c.getRules().getType().isCreature())creatures+=n;
        if(c.getRules().getType().hasSubtype("Dragon"))dragons+=n;
        if(c.getRules().getType().isLand())lands+=n;
      }
      r.put("creaturesMain",creatures);r.put("dragonsMain",dragons);r.put("conventionalLands",lands);r.put("recognizedMain",names);
      r.put("pass",problem==null && d.getMain().countAll()==99 && d.get(DeckSection.Commander).countAll()==1);
      pass &= Boolean.TRUE.equals(r.get("pass"));results.add(r);
    }
    Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(results));
    System.out.println("VERIFICATION "+(pass?"PASS":"FAIL"));
    System.exit(pass?0:2);
  }
}
