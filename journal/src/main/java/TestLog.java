import io.atomix.storage.journal.SegmentedJournal;
import io.atomix.storage.journal.SegmentedJournalReader;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Serializer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

//mensagem para ser enviada!

class TransactionID{
    public int xid;
    public String coordenador;

    public TransactionID(int xid, String coordenador) {
        this.xid = xid;
        this.coordenador = coordenador;
    }

    @Override
    public String toString() {
        return "TransactionID{" +
                "xid=" + xid +
                ", coordenador='" + coordenador + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionID that = (TransactionID) o;
        return xid == that.xid &&
                coordenador.equals(that.coordenador);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xid, coordenador);
    }
}

class Transaction{
    public TransactionID xid;
    public String resultado; //pode ser I; A ou C; F para o controlador
                            //pode ser P ou A; A ou C para o participante

    //Estas variaveis são para o Coodenador ... Criar uma class com extends?
    public HashSet<Address> quaisResponderam = new HashSet<>();
    public HashMap<Address, Object> participantes; //pode-se mudar para ser modular
    //so podemos fazer commit quando o tamanho dos dois maps forem iguais!
    public CompletableFuture<Boolean> terminada = new CompletableFuture<Boolean>();

    public TreeSet<Participante> locksObtidos = new TreeSet<>();

    public PedidoID pedido; //id do pedido para esta transacao

    public Transaction(TransactionID xid, String resultado){
        this.xid = xid;
        this.resultado = resultado;

    }

    public Transaction(TransactionID xid, String resultado, HashMap<Address, Object> participantes, PedidoID p) {
        this.xid = xid;
        this.resultado = resultado;
        this.participantes = participantes;

        for(Address a: participantes.keySet()){
            Participante part = new Participante();
            part.endereco = a;
            locksObtidos.add(part);
        }

        pedido = p;
    }
}


public class TestLog {
    public static void leLog(SegmentedJournal<Object> log){
        SegmentedJournalReader<Object> readLog = log.openReader(0);
        while(readLog.hasNext()){
            LogEntry e = (LogEntry) readLog.next().entry();
            System.out.println(e);
        }
    }

    public static void leLogs(Address e[]){
        Serializer ser = DBKeyValueProtocol.newSerializer();


        for(int i = 0; i < e.length; i++){
            SegmentedJournal<Object> log = SegmentedJournal.builder()
                    .withName("exemploIDCoordenador" + e[i])
                    .withSerializer(ser)
                    .build();
            System.out.println("-------------------------------IDC:"+i+"-----------------------------");
            leLog(log);

            SegmentedJournal<Object> logP = SegmentedJournal.builder()
                    .withName("exemploIDParticipante" + e[i])
                    .withSerializer(ser)
                    .build();
            System.out.println("-------------------------------IDP:"+i+"-----------------------------");
            leLog(logP);
        }
    }

    public HashMap<Long, byte[]> atualizaValores(HashMap<Long, byte[]> valores,HashMap<Long, byte[]> novos){

        valores.putAll(novos);
        return valores;
    }

    public static void main(String[] args) {

        Address[] end = {
                Address.from("localhost:23451"),
                Address.from("localhost:23452"),
                Address.from("localhost:23453"),
                //Address.from("localhost:23454"),
                //Address.from("localhost:23455"),
        };

        int id = Integer.parseInt(args[0]);
        if(id==-1){
            leLogs(end);
        }
        else {
            Server s = new Server(end, end[id]);
        }

    }
}
