package com.qfang.examples.telnet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * nio + thread pool
 * Created by walle on 2017/3/12.
 */
public class NioTelnetServer {

    public static void main(String[] args) throws IOException {
        NioTelnetServer.start();
    }

    public static void start() throws IOException {
        ExecutorService executorService = Executors.newFixedThreadPool(10);

        Selector selector = Selector.open();
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        InetSocketAddress address = new InetSocketAddress(9090);
        serverSocketChannel.socket().bind(address);
        System.out.println("telnet server start at " + address);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            int selectNum = selector.select(1000);
            if(selectNum == 0)
                continue;

            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if(key.isAcceptable()) {
                    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                    SocketChannel socketChannel = serverChannel.accept();
                    socketChannel.configureBlocking(false);
                    // 注册该 socketChannel 上的读写事件，并且绑定一个 attachment，这个 attachment 可以在下面通过 selectionKey.attachment() 获取到
                    // 注：对于同一个 socket 链接，后面的读写事件获取的 SelectionKey 都是同一个对象，因此获取到的 attachment 对象也是同一个对象
                    // 所以需要注意的是：如果读写事件的处理都是放在业务线程池中执行的话，需要注意多线程对 attachment 对象的同步问题
                    socketChannel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(1024));
                    socketChannel.write(ByteBuffer.wrap("welcome telnet server ... \r\n".getBytes()));
                } else if(key.isReadable()) {
                    // 先取消掉监听读事件，否则下次循环还会进到这里来
                    key.interestOps(key.interestOps()&(~SelectionKey.OP_READ));
                    executorService.execute(new ReadEventHandler(key));
                } else if(key.isWritable()) {
                    // 同样的如果写 buffer 可用，需要先取消掉写事件，否则会一直进到这里
                    key.interestOps(key.interestOps()&(~SelectionKey.OP_WRITE));
                    executorService.execute(new WriteEventHandler(key));
                }
                keyIterator.remove();
            }
        }
    }

    private static class ReadEventHandler implements Runnable {

        private final SelectionKey selectionKey;

        private ReadEventHandler(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public void run() {
            System.out.println("receive read event");
            try {
                // 每个 selectionKey 都只会绑定到一个 SocketChannel 中，一个 SocketChannel 相当于一个 Socket
                SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
                ByteBuffer buffer = ByteBuffer.allocate(512);
                // 非阻塞模式下,read()方法在尚未读取到任何数据时可能就返回了。所以需要关注它的int返回值，它会告诉你读取了多少字节。
                int size = socketChannel.read(buffer);

                // 这里将数据读取放到 attachment 中，当然这里也可以将数据读取之后直接使用
                // socketChannel.write(attachment); 方式将数据之间写回 socket
                // 但是这里的做法没有直接写回，而是重新注册了 Write 事件，然后在下一个 write 事件时读取 attachment 中的数据并写好 socket 中

                if(size == -1) {  // 链接关闭
                    socketChannel.close();
                } else {
                    // 读取到了数据，将读取到的数据绑定到 selectionKey 上面，交给写事件处理
                    // 每次绑定的都是一个新的 ByteBuffer 对象，避免 attachment 对象的同步问题
                    selectionKey.attach(buffer);

                    // 读完之后只注册写事件，先保证写事件执行完成后，再注册读事件
                    selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                    selectionKey.selector().wakeup();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private static class WriteEventHandler implements Runnable {

        private final SelectionKey selectionKey;

        private WriteEventHandler(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public void run() {
            System.out.println("receive write event");

            // 这里的 selectionKey socketChannel 和之前读使用的都是同一个对象
            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
            ByteBuffer attachment = (ByteBuffer) selectionKey.attachment();
            attachment.flip();
            if(attachment.hasRemaining()) {
                byte[] readMsg = new byte[attachment.remaining()];
                attachment.get(readMsg);

                ByteBuffer writeBuffer = ByteBuffer.allocate(1024);
                writeBuffer.put("\r\nFollow you: ".getBytes(Charset.forName("UTF-8")));
                writeBuffer.put(readMsg);
                writeBuffer.put("\r\n".getBytes(Charset.forName("UTF-8")));
                writeBuffer.flip();
                try {
                    // writeBuffer 写之前先要 flip 一下，否则不会有任何数据写出去
                    // 非阻塞模式下，write()方法在尚未写出任何内容时可能就返回了，所以需要在循环中调用write()
                    while(writeBuffer.hasRemaining()) {
                        socketChannel.write(writeBuffer);
                    }

                    selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
                    selectionKey.selector().wakeup();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
