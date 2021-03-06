import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.storage.journal.SegmentedJournal;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Serializer;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

class TwoPCParticipante extends TwoPC{

    private HashMap<TransactionID, Transaction> transacoes = new HashMap<>();

    /**
     * @valores Para guadar os pares chave-valor
     */
    private InterfaceParticipante valores;

    /**
     * Para o 2PL:
     * Uma variável que diz quem tem o lock atual
     * Uma lista de possiveis locks, já com uma ordem associada
     */


    private Locking lock;

    private CompletableFuture<Void> enviaMensagem(Msg m, String assunto, Address a){

        //return CompletableFuture.allOf(esperar).thenAccept(v -> {
        try{
            return ms.sendAsync(a,assunto,s.encode(m));
        }
        catch(Exception e){
            System.out.println("Erro enviar mensagem: " + e); //podemos é por a remover
        }
        return CompletableFuture.completedFuture(null);
        //});
    }

    private CompletableFuture<Void> enviaOk(Msg m, Address coord) {

        return enviaMensagem(m,"ok",coord);
    }

    private CompletableFuture<Void> enviaPrepared(Msg m, Address coord){

        return enviaMensagem(m,"prepared",coord);
    }

    private CompletableFuture<Void> enviaAbort(Msg m, Address coord){

        return enviaMensagem(m,"abort",coord);
    }

    private void analisaTransacaoParticipante() {
        //Resultados possiveis :
        for (Transaction t : transacoes.values()) {
            Msg paraMandar = new Msg(t.xid,null);
            switch (t.resultado) {
                case ("P"):

                    LockGlobal l = valores.novoLock(t.xid,t.participantes.get(meuEnd));

                    lock.lock(l)
                    .thenAccept(v -> {
                        enviaPrepared(paraMandar, Address.from(paraMandar.id.coordenador));
                    });

                    break;
                case ("A"):
                    enviaAbort(paraMandar,Address.from(paraMandar.id.coordenador));
                    break;

                case ("C"):
                    enviaOk(paraMandar,Address.from(paraMandar.id.coordenador));
                    break;
            }
        }
    }


    public void recuperaLogParticipante(){

        while(readerLog.hasNext()) {

            // Leitura do log
            LogEntry e = (LogEntry) readerLog.next().entry();


            Transaction t = transacoes.get(e.xid);

            if (t == null) {

                if (e.data.equals("C"))
                    valores.atualizaValores(e.valores);

                HashMap<Address,Object> valoresEnd = new HashMap<>();
                valoresEnd.put(meuEnd,e.valores);
                t = new Transaction(e.xid, e.data,valoresEnd,null);
                transacoes.put(e.xid, t);



            }

            else {
                //Altera o resultado
                t.resultado = e.data;
                if (e.data.equals("C"))
                    valores.atualizaValores(e.valores);

            }

        }

        analisaTransacaoParticipante();

    }

    private CompletableFuture<Void> enviaMensagemGet(MsgGet msgGet, String assunto, Address a){

        System.out.println("Enviar mensagem com resposta ao get " + assunto + " a: " + a);

        System.out.println("Vou enviar!");

        byte [] m = s.encode(msgGet);

        try{
            System.out.println("Vou mm tentar enviar");
            return ms.sendAsync(a, assunto, m);
        }
        catch(Exception e){
            System.out.println("Erro enviar mensagem: " + e); //podemos é por a remover
        }
        return CompletableFuture.completedFuture(null);

    }





    public TwoPCParticipante(Address[] e, Address id, ManagedMessagingService ms,
                             Serializer ser, InterfaceParticipante valores, Locking lockA,
                             ScheduledExecutorService ses){

        super(e,id,ms,ser, ses);

        log = SegmentedJournal.builder()
                .withName("exemploIDParticipante" + this.meuEnd)
                .withSerializer(s)
                .build();

        readerLog = log.openReader(0);
        writerLog = log.writer();

        this.valores = valores;
        this.lock = lockA;

        //System.out.println("TamEnd: " + end.length);

        recuperaLogParticipante();

        ms.registerHandler("preparedCoordenador", (a,m)-> {
            try{
                Msg nova = s.decode(m);


            /**
             * Vou verificar primeiro se já recebi uma resposta para este notificação,
             * se sim tenho de dar a mesma
             */
            if(transacoes.containsKey(nova.id) &&
                    !transacoes.get(nova.id).resultado.equals("P")){
                System.out.println("Ja tenho esta transacao!Decidir o que fazer com o lock!");
                Msg paraMandar = new Msg(nova.id,null);

                String resultado = transacoes.get(nova.id).resultado;
                switch (resultado){
                    case "A": //o resultado so pode ser abort
                        enviaAbort(paraMandar,Address.from(paraMandar.id.coordenador));
                        break;
                }
                return;
            }
            String assunto = "prepared";

            /**
             * Vou guardar a minha decisão no Log "P" <- Estou pronto para iniciar
             * Vou criar uma nova entrada na transacao
             * */
            if(!transacoes.containsKey(nova.id)){
                writerLog.append(new LogEntry(nova.id, "P", nova.valores));

                Transaction t = new Transaction(nova.id, "P");
                transacoes.put(nova.id, t);

            }

            LockGlobal meuLock = valores.novoLock(nova.id,nova.valores);
            CompletableFuture<Void> obtido = lock.lock(meuLock);

            System.out.println("Passei lock!");

            obtido.thenAccept(v -> {

                Msg paraMandar = new Msg(nova.id,null);

                System.out.println("Posso enviar prepared ao coordenador: " + nova.id.coordenador + "!\n\n");
                enviaPrepared(paraMandar, Address.from(paraMandar.id.coordenador));
            });

            }catch(Exception excp){
                System.out.println(excp);
            }

        }, es);

        ms.registerHandler("abortCoordenador", (a,m)-> {
            Msg nova = s.decode(m);


            LockGlobal auxL = valores.novoLock(nova.id,nova.valores);
            lock.unlock(auxL);

            if(transacoes.containsKey(nova.id) == false){
                System.out.println("Não foi possivel abortar a transacao ..." + nova.id);
            }
            else{
                /**
                 * Para o abort se ja tiver um abort ignoramos, senão tenho de guardar o resultado
                 */
                if(transacoes.get(nova.id).resultado.equals("A")){

                }else {
                    writerLog.append(new LogEntry(nova.id, "A", null));
                    Transaction t = transacoes.get(nova.id);
                    t.resultado = "F";
                    transacoes.put(nova.id, t);
                }
            }

            Msg paraMandar = new Msg(nova.id,null);
            //LIBERTAR LOCK
            enviaOk(paraMandar,Address.from(paraMandar.id.coordenador));


        }, es);

        ms.registerHandler("commitCoordenador", (a,m)-> {
            MsgCommit nova = s.decode(m);

            if(transacoes.containsKey(nova.id) == false){
                System.out.println("Não foi possivel efetuar commit da transacao ... " + nova.id);
            }
            else{
                /**
                 * Primeiro verifico se já não guardei a transacao
                 */
                if(transacoes.get(nova.id).resultado.equals("C")){
                    //se ja tem resposta, responde e n faz nada
                }else {
                    /**
                     * Tarefas:
                     * --> Guardar os valores
                     * --> Guardar o resultado no log
                     * --> Adicionar a transacao no hashmap
                     * --> Libertar o lock
                     */
                    System.out.println("Vou guardar os valores para a transacao " + nova.id);

                    this.valores.atualizaValores(nova.valores);
                    writerLog.append(new LogEntry(nova.id, "C", this.valores.getValores()));

                    Transaction t = transacoes.get(nova.id);
                    t.resultado = "C";
                    transacoes.put(nova.id, t);
                    LockGlobal lockAux = valores.novoLock(t.xid,nova.valores);
                    lock.unlock(lockAux);

                }
            }

            Msg paraMandar = new Msg(nova.id,null);
            //LIBERTAR LOCK

            enviaOk(paraMandar,Address.from(paraMandar.id.coordenador));


        }, es);

    }
}

