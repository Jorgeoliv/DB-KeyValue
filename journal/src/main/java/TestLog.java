import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.impl.NettyMessagingService;
import io.atomix.storage.journal.SegmentedJournal;
import io.atomix.storage.journal.SegmentedJournalReader;
import io.atomix.storage.journal.SegmentedJournalWriter;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Serializer;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

//mensagem para ser enviada!
class Msg {
    int id;

    public Msg(int id) {
        this.id = id;
    }
}

//mensagem para ser enviado do coordenador ao participante para o commit
class MsgCommit extends Msg {
    HashMap<Long,Byte[]> valores;

    public MsgCommit(int id, HashMap<Long, Byte[]> valores) {
        super(id);
        this.valores = valores;
    }
}


class LogEntry {
    public int xid;
    public String data;
    public HashMap<Long,Byte[]> valores;

    public LogEntry() {}
    public LogEntry(int xid, String data, HashMap<Long, Byte[]> valores) {
        this.xid=xid;
        this.data=data;
        this.valores = valores;
    }

    public String toString() {
        return "xid="+xid+" "+data;
    }
}

class Transaction{
    public int xid;
    public String resultado; //pode ser I; A ou C; F para o controlador
                            //pode ser P ou A; A ou C para o participante
    public HashSet<Address> quaisResponderam = new HashSet<>();
    public HashMap<Address, HashMap<Long, Byte[]>> participantes;
    //so podemos fazer commit quando o tamanho dos dois maps forem iguais!

    public Transaction(int xid, String resultado, HashMap<Address, HashMap<Long, Byte[]>> participantes) {
        this.xid = xid;
        this.resultado = resultado;
        this.participantes = participantes;
    }
}

class TwoPCParticipante{

    private HashMap<Integer, ArrayList<LogEntry>> transacoes = new HashMap<>();
    private SegmentedJournal<Object> log;
    private SegmentedJournalReader<Object> readerLog;
    private SegmentedJournalWriter<Object> writerLog;
    private HashMap<Integer, String> resultadoTransacoes = new HashMap<>();
    private HashMap<Integer, String> minhasRespostas = new HashMap<>();

    private Address[] end;
    private int meuID;
    private ManagedMessagingService ms;
    private Serializer s;
    //private Consumer<Msg> handlerMensagem;

    private void analisaTransacaoParticipante(){
        System.out.println("AnalisaPart");
        //as transacoes que estao completas (com C ou A) têm 2 entradas na lista
        //as que não foram tratadas apenas têm uma
        ArrayList<LogEntry> naoTratadas = new ArrayList<>();
        for(ArrayList<LogEntry> ent : transacoes.values()){

            if(ent != null){
                if(ent.size() < 2){
                    System.out.println("Entrada com menos de 2");
                    LogEntry entrada = ent.get(0);
                    if(entrada != null && !entrada.data.equals("A")){ //se a entrada não é A, então é P e
                        // a transação ainda não foi tratada
                        minhasRespostas.put(entrada.xid,"prepared");
                        System.out.println("Entrada nao tratada");
                        naoTratadas.add(entrada);
                    }
                    else{
                        if(entrada != null){
                            minhasRespostas.put(entrada.xid,"abort");
                            resultadoTransacoes.put(entrada.xid,entrada.data);
                            Msg paraMandar = new Msg(entrada.xid);
                            try{

                                ms.sendAsync(end[0], "abort", s.encode(paraMandar));
                            }catch(Exception excep){
                                System.out.println(excep);
                            }
                        }
                    }
                }
                else{
                    LogEntry resultadoAux = ent.get(1);
                    resultadoTransacoes.put(resultadoAux.xid,resultadoAux.data);
                    minhasRespostas.put(resultadoAux.xid,(resultadoAux.data.equals("A") ? "abort" : "prepared"));
                }
            }
        }

        System.out.println("----------Resultado das transacoes---------");
        for(Map.Entry<Integer,String> resAux: resultadoTransacoes.entrySet()){
            System.out.println("XID: " + resAux.getKey() + "!Res: " + resAux.getValue() + "!");
        }

        System.out.println("------------Transações não tratadas--------");
        for(LogEntry entrada: naoTratadas){
            //mandar mensagem a perguntar se estão preparados
            System.out.println("XID: " + entrada.xid);

            Msg paraMandar = new Msg(entrada.xid);
            try{

                ms.sendAsync(end[0], "prepared", s.encode(paraMandar));
            }catch(Exception excep){
                System.out.println(excep);
            }


        }
    }

    public void recuperaLogParticipante(){
        System.out.println("Recupera Part");
        while(readerLog.hasNext()) {
            LogEntry e = (LogEntry) readerLog.next().entry();
            System.out.println(e.toString());
            ArrayList<LogEntry> aux = transacoes.get(e.xid); //a lista no maximo tem uma entrada para o participante
            if(aux == null){
                aux = new ArrayList<LogEntry>();
            }
            aux.add(e);
            transacoes.put(e.xid,aux);
        }

        analisaTransacaoParticipante();

    }

    public TwoPCParticipante(Address[] e, int id){

        s = Serializer.builder()
                .withTypes(Msg.class)
                .withTypes(LogEntry.class)
                .build();

        log = SegmentedJournal.builder()
                .withName("exemploID" + id)
                .withSerializer(s)
                .build();

        ExecutorService es = Executors.newSingleThreadExecutor();

        meuID = id;
        int k = 0;
        end = new Address[e.length];
        System.out.println("TamE: " + e.length);
        for(Address aux: e){
            end[k++] = aux;
        }

        System.out.println("TamEnd: " + end.length);

        ms = NettyMessagingService.builder()
                .withAddress(end[meuID])
                .build();
        ms.start();

        readerLog = log.openReader(0);
        writerLog = log.writer();
        if(id==0){
            System.out.println("Erro!");
            return;
        }
        else{
            recuperaLogParticipante();
        }

        System.out.println("Passei recupera log!");

        System.out.println("Vou registar handlers!");
        ms.registerHandler("prepared", (a,m)-> {
            Msg nova = s.decode(m);
            String minhaResposta = minhasRespostas.get(nova.id);
            if(minhaResposta != null){
                //já tenho resposta a esta transacao, dou a mm
                Msg paraMandar = new Msg(nova.id);
                ms.sendAsync(end[0], minhaResposta, s.encode(paraMandar));
                return;
            }

            //caso não tenha resposta pergunto ao user
            System.out.println("Quer participar na transação: " + nova.id + "? (0 para não)");
            int res = 1;
            String assunto = "prepared";

            Scanner sc = new Scanner(System.in);
            try{
                String resAux = sc.next();
                res = Integer.parseInt(resAux);
            }
            catch(Exception excep){}

            if(res == 0){
                assunto = "abort";
                writerLog.append(new LogEntry(nova.id,"A"));
                resultadoTransacoes.put(nova.id,"A");
            }
            else{
                writerLog.append(new LogEntry(nova.id,"P"));
            }
            minhasRespostas.put(nova.id,assunto); //guardo a minha resposta


            //mandar mensagem ao controlador
            Msg paraMandar = new Msg(nova.id);
            ms.sendAsync(end[0], assunto, s.encode(paraMandar));

        }, es);

        ms.registerHandler("abort", (a,m)-> {
            Msg nova = s.decode(m);

            if(resultadoTransacoes.get(nova.id) != null){
                System.out.println("asneira");
            }
            else{
                System.out.println("Abortar transacao: " + nova.id);
                resultadoTransacoes.put(nova.id, "A");
                writerLog.append(new LogEntry(nova.id, "A"));
            }

        }, es);

        ms.registerHandler("commit", (a,m)-> {
            Msg nova = s.decode(m);

            if(resultadoTransacoes.get(nova.id) != null){
                System.out.println("asneiraCCCCC");
            }
            else{
                System.out.println("Realizar transacao: " + nova.id);
                resultadoTransacoes.put(nova.id, "C");
                writerLog.append(new LogEntry(nova.id, "C"));
            }
        }, es);
    }
}


class TwoPCControlador{

    private final int DELTA = 10;
    private ArrayList<Integer> paraCancelar = new ArrayList<>(); //ids com as proximas a serem canceladas
    private ArrayList<Boolean> possoCancelar = new ArrayList<>(); //valores bools que indicam se podemos ou não apagar
    private ArrayList<Integer> paraTerminar = new ArrayList<>(); //ids com as proximas a serem terminadas
    private ArrayList<Boolean> possoTerminar = new ArrayList<>(); //valores bools que indicam se podemos ou não terminar

    //private HashMap<Integer, ArrayList<LogEntry>> transacoesLog = new HashMap<>(); //para guardar as transacoes
    // dos logs

    private SegmentedJournal<Object> log;
    private SegmentedJournalReader<Object> readerLog;
    private SegmentedJournalWriter<Object> writerLog;


    private HashMap<Integer, Transaction> transacoes = new HashMap<>();

    private Address[] end;
    private int meuID;
    private ManagedMessagingService ms;
    private Serializer s;
    private int xid;
    private ScheduledExecutorService es;
    //private Consumer<Msg> handlerMensagem;

    private void analisaTransacaoControlador(){

        for(Transaction t: transacoes.values()){
            if(t.resultado.equals("I")){
                //mandar mensagem para todos prepared
                Msg paraMandar = new Msg(t.xid);
                paraCancelar.add(t.xid);
                possoCancelar.add(true);
                for(Address a: t.participantes.keySet())
                    ms.sendAsync(a, "prepared", s.encode(paraMandar));
                //dar tempo para a resposta
                es.schedule( ()-> {
                    int idT = paraCancelar.remove(0);
                    boolean cancelo = possoCancelar.remove(0);

                    if(cancelo) {
                        writerLog.append(new LogEntry(idT,"A",null));
                        transacoes.get(idT).resultado = "A";
                        paraTerminar.add(idT);
                        possoTerminar.add(false);
                        System.out.println("Res: " + transacoes.get(idT).resultado);
                        Msg paraMandarAux = new Msg(idT);
                        for(Address a : transacoes.get(idT).participantes.keySet())
                            ms.sendAsync(a, "abort", s.encode(paraMandarAux));


                        es.schedule(() -> {
                            cicloTerminar();
                        }, DELTA, TimeUnit.SECONDS);
                    }
                }, DELTA, TimeUnit.SECONDS);
            }
            else{
                if(t.resultado.equals("C")){
                    //mandar mensagem para todos commit
                    paraTerminar.add(t.xid);
                    possoTerminar.add(false);
                    System.out.println("Transacao efetuada com sucesso!");

                    //mandar mensagem a todos a dizer commit
                    MsgCommit msgC = new MsgCommit(t.xid,null);
                    for(Address a: t.participantes.keySet()) {
                        msgC.valores = t.participantes.get(a);
                        ms.sendAsync(a, "commit", s.encode(msgC));
                    }


                    es.schedule( ()-> {
                        cicloTerminar();
                    }, DELTA, TimeUnit.SECONDS);

                }
                else{
                    if(t.resultado.equals("A")){
                        //mandar mesnsagem para todos abort
                        Msg paraMandar = new Msg(t.xid);
                        paraTerminar.add(t.xid);
                        possoTerminar.add(false);
                        for(Address a: t.participantes.keySet())
                            ms.sendAsync(a, "abort", s.encode(paraMandar));


                        es.schedule( ()-> {
                            cicloTerminar();
                        }, DELTA, TimeUnit.SECONDS);

                    }
                }
            }
        }
        //caso n seja nenhum é F, e n é preciso fazer nada (para já antes de apagar logs)
    }

    public void recuperaLogControlador(){
        xid = -1;

        while(readerLog.hasNext()) {
            LogEntry e = (LogEntry) readerLog.next().entry();
            if(e.xid > xid){
                xid = e.xid;
            }
            //verifica se a transacao já existe no mapa
            Transaction t = transacoes.get(e.xid);
            if(t == null) {
                //caso não exista cria e adiciona ao mapa
                HashMap<Address, HashMap<Long, Byte[]>> part = null;
                //System.out.println(e.toString());
                if (e.valores != null) {
                    //se existirem valores (em caso de I ou C) entam vai buscar os participantes envolvidos e cada valor
                    // para o participante
                    part = participantesEnvolvidos(e.valores);
                }

                t = new Transaction(e.xid, e.data, part);
                transacoes.put(e.xid, t);
            }
            else{
                //caso exista apenas altera o resultado
                t.resultado = e.data;
            }
        }

        analisaTransacaoControlador();

    }


    public TwoPCControlador(Address[] e, int id){

        s = Serializer.builder()
                .withTypes(Msg.class)
                .withTypes(LogEntry.class)
                .withTypes(MsgCommit.class)
                .build();

        log = SegmentedJournal.builder()
                .withName("exemploID" + id)
                .withSerializer(s)
                .build();

        es = Executors.newSingleThreadScheduledExecutor();

        meuID = id;
        int k = 0;
        end = new Address[e.length];
        System.out.println("TamE: " + e.length);
        for(Address aux: e){
            end[k++] = aux;
        }

        System.out.println("TamEnd: " + end.length);

        ms = NettyMessagingService.builder()
                .withAddress(end[meuID])
                .build();
        ms.start();

        readerLog = log.openReader(0);
        writerLog = log.writer();

        if(id == 0){
            recuperaLogControlador();
        }
        else{
            System.out.println("Erro!");
            return;
        }

        System.out.println("Passei recupera log!");

        System.out.println("Sou o controlador!");
        //controlador tem de registar handler para ao receber um Prepared ou Abort

        ms.registerHandler("ok", (o,m) -> {
            System.out.println("Recebi um ok! " + o);
            try {
                Msg nova = s.decode(m);

                //pensar depois para o caso em que a transacao está terminada

                if(transacoes.get(nova.id).resultado.equals("I")){
                    System.out.println("Conflito no OK");
                }

                else{
                    //ainda não existe resultado
                    Transaction t = transacoes.get(nova.id);
                    t.quaisResponderam.add(o);


                    if(t.quaisResponderam.size() == t.participantes.size()){
                        //já responderam todos e pode-se mandar fazer commit
                        writerLog.append(new LogEntry(nova.id, "F", null));
                        t.resultado = "F";
                        int indiceAux = paraTerminar.indexOf(Integer.valueOf(nova.id));
                        possoTerminar.set(indiceAux, true);
                        System.out.println("Transacao finalizada com sucesso!");
                    }
                }
            }


            catch(Exception exc){
                System.out.println("exc: " + exc);
            }
        }, es);

        ms.registerHandler("prepared", (o,m)->{
            System.out.println("Recebi prepared!" + o);
            try {
                Msg nova = s.decode(m);

                //pensar depois para o caso em que a transacao está terminada

                if(!transacoes.get(nova.id).resultado.equals("I")){
                    //já existe resultado diferente de I e então pode-se mandar mensagem consoante esse resultado

                    if(!transacoes.get(nova.id).resultado.equals("F")) {
                        //e o resultado n é F
                        String assunto = "commit"; //vamos mandar commit caso o resultado n seja A

                        if (transacoes.get(nova.id).resultado.equals("A")) {
                            assunto = "abort"; //vamos mandar abort caso o resultado seja A
                        }

                        //mandar mensagem para o que enviou
                        Msg paraMandar = new Msg(nova.id);
                        ms.sendAsync(o, assunto, s.encode(paraMandar));
                    }
                }

                else{
                    //ainda não existe resultado
                    Transaction t = transacoes.get(nova.id);
                    t.quaisResponderam.add(o);


                    if(t.quaisResponderam.size() == t.participantes.size()){
                        //já responderam todos e pode-se mandar fazer commit
                        writerLog.append(new LogEntry(nova.id, "C", t.participantes.values()
                                .stream().reduce(new HashMap<>(), (r,n) -> { r.putAll(n); return r;})));
                        t.resultado = "C";
                        t.quaisResponderam = new HashSet<>();
                        int indiceAux = paraCancelar.indexOf(Integer.valueOf(nova.id));
                        possoCancelar.set(indiceAux,false);
                        paraTerminar.add(nova.id);
                        possoTerminar.add(false);
                        System.out.println("Transacao efetuada com sucesso!");

                        //mandar mensagem a todos a dizer commit
                        for(Address a: t.participantes.keySet()) {
                            MsgCommit msgC = new MsgCommit(nova.id, t.participantes.get(a));
                            ms.sendAsync(a, "commit", s.encode(msgC));
                        }

                        es.schedule( ()-> {
                            cicloTerminar();
                        }, DELTA, TimeUnit.SECONDS);

                    }
                }
            }


            catch(Exception exc){
                System.out.println("exc: " + exc);
            }
        },es);

        ms.registerHandler("abort", (a,m)-> {
            Msg nova = s.decode(m);
            System.out.println("Recebi abort: " + nova.id + "!" + a);

            if(transacoes.get(nova.id).resultado.equals("A")){
                //já tem resultado, pelo que já mandou para todos e só mandamos para o que enviou
                Msg paraMandar = new Msg(nova.id);
                ms.sendAsync(a, "abort", s.encode(paraMandar));
            }
            else {
                if (transacoes.get(nova.id).resultado.equals("C") || transacoes.get(nova.id).resultado.equals("F")) {
                    //conflito nos resultados
                    System.out.println("Conflito nos resultados no controlador ao receber abort!");
                }
                else {
                    //pode-se por o resultado a A, pois um abortou
                    writerLog.append(new LogEntry(nova.id, "A", null));

                    transacoes.get(nova.id).resultado = "A";
                    int indiceAux = paraCancelar.indexOf(Integer.valueOf(nova.id));
                    possoCancelar.set(indiceAux, false);
                    paraTerminar.add(nova.id);
                    possoTerminar.add(false);
                    System.out.println("Transacao abortada com sucesso!");


                    //mandar mensagem para todos os participantes daquela transacao
                    Msg paraMandar = new Msg(nova.id);
                    for (Address ad : transacoes.get(id).participantes.keySet())
                        ms.sendAsync(ad, "abort", s.encode(paraMandar));

                    es.schedule(() -> {
                        cicloTerminar();
                    }, DELTA, TimeUnit.SECONDS);


                }
            }
        }, es);
    }

    private void cicloTerminar(){
        int idT = paraTerminar.remove(0);
        boolean acabou = possoTerminar.remove(0);

        if(acabou) {
            System.out.println("Esquecer a Transacao!");
        }else{
            paraTerminar.add(idT);
            possoTerminar.add(false);


            Transaction t = transacoes.get(idT);
            HashSet<Address> naoResponderam = new HashSet(t.participantes.keySet());
            naoResponderam.removeAll(t.quaisResponderam);

            if(t.resultado.equals("A")){
                Msg msg = new Msg(t.xid);
                for(Address a: naoResponderam)
                    ms.sendAsync(a, t.resultado, s.encode(msg));
            }else{
                MsgCommit msg = new MsgCommit(t.xid, null);
                for(Address a: naoResponderam){
                    msg.valores = t.participantes.get(a);
                    ms.sendAsync(a, t.resultado, s.encode(msg));
                }
            }

            es.schedule( ()-> {
                cicloTerminar();
            }, DELTA, TimeUnit.SECONDS);
        }

    }

    public HashMap<Address,HashMap<Long,Byte[]>> participantesEnvolvidos(HashMap<Long, Byte[]> valores){
        HashMap<Address,HashMap<Long,Byte[]>> participantes = new HashMap<>();
        for(Long aux : valores.keySet()){
            int resto = (int)(aux % (end.length-1)) + 1; //para já o coordenador n participa
            HashMap<Long,Byte[]> auxiliar = participantes.get(end[resto]);
            if(auxiliar == null){
                auxiliar = new HashMap<>();
            }
            auxiliar.put(aux,valores.get(aux));
            participantes.put(end[resto],auxiliar);
        }
        return participantes;
    }

    public void iniciaTransacao(HashMap<Long,Byte[]> valores){
        //depois tem de perguntar sempre se quer realizar transação
        while(true){
            try {
                xid++;
                System.out.println("Carregue enter para realizar a transação " + xid);
                int read = System.in.read();
                //mandar mensagem para todos para iniciar transacao
                Msg paraMandar = new Msg(xid);
                writerLog.append(new LogEntry(xid,"I",valores));
                HashMap<Address,HashMap<Long,Byte[]>> participantes = participantesEnvolvidos(valores);
                Transaction novaTransacao = new Transaction(xid, "I", participantes);

                for(Address a: participantes.keySet()){
                    System.out.println("Vou mandar a: " + a);
                    ms.sendAsync(a, "prepared", s.encode(paraMandar));
                }

                paraCancelar.add(paraMandar.id); //adicionar este id ao array para cancelar a transacao com o id
                possoCancelar.add(true); //para já podemos cancelar (até que alguem ponha resposta)

                es.schedule( ()-> {
                    int idT = paraCancelar.remove(0);
                    boolean cancelo = possoCancelar.remove(0);

                    if(cancelo) {
                        writerLog.append(new LogEntry(idT,"A",null));
                        transacoes.get(idT).resultado = "A";
                        paraTerminar.add(idT);
                        possoTerminar.add(false);
                        System.out.println("Res: " + transacoes.get(idT).resultado);
                        Msg paraMandarAux = new Msg(idT);
                        for(Address a : transacoes.get(idT).participantes.keySet())
                            ms.sendAsync(a, "abort", s.encode(paraMandarAux));


                        es.schedule(() -> {
                            cicloTerminar();
                        }, DELTA, TimeUnit.SECONDS);
                    }
                }, DELTA, TimeUnit.SECONDS);

            }catch(Exception excep){System.out.println(excep);}
        }
    }
}

public class TestLog {
    public static void main(String[] args) {

        Address[] end = {
                Address.from("localhost:23451"),
                Address.from("localhost:23452"),
                Address.from("localhost:23453"),
                //Address.from("localhost:23454"),
                //Address.from("localhost:23455"),
        };

        int id = Integer.parseInt(args[0]);
        if(id == 0){
            TwoPCControlador tpc = new TwoPCControlador(end,id);
        }
        else{
            TwoPCParticipante tpp = new TwoPCParticipante(end, id);
        }

    }
}