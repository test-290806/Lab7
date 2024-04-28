package server;

import common.Commands.HelpCommand;
import common.Constants;
import common.Exceptions.*;
import common.UI.CommandReader;
import common.UserInfo;
import common.net.requests.*;
import common.Commands.UserCommand;
import common.UI.Console;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.Commands.*;
import server.Controllers.CollectionController;
import common.Controllers.CommandsController;
import server.Controllers.DBController;
import server.Controllers.DataFileController;

import java.io.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.sql.*;
import java.util.*;

/**
 * Main class for server app
 */
public class Main {
    public static final Logger logger = LoggerFactory.getLogger(Main.class);
    /**
     * Server object
     */
    public static UDPServer server;
    /**
     * Selector object for handling many clients
     */
    private static Selector selector;
    /**
     * Controller of collection
     */
    private static CollectionController collectionController;
    /**
     * Controller of commands
     */
    private static CommandsController clientCommandsController;

    private static CommandsController serverCommandsController;
    /**
     * Controller of data file
     */
    private static DataFileController dataFileController;
    /**
     * Reader for checking if admin console input is ready
     */
    private static Reader reader;

    /**
     * Main method of program
     * <p>Calls methods to load data file, init all controllers, run server and start handling client commands
     * @param args (not used)
     */
    public static void main(String[] args) {
        logger.info("Logger for server started");

        reader = new InputStreamReader(System.in);
        Console.getInstance().setScanner(new Scanner(reader));
        logger.info("Console handler was initialized successfully");
        server = new UDPServer(Constants.serverPort);
        try {
            server.open();
            selector = Selector.open();
            server.registerSelector(selector, SelectionKey.OP_READ);
            logger.info("Server started successfully");
        } catch (IOException e) {
            logger.error("Error while starting server!", e);
            System.exit(0);
        }

        try {
            DBController.getInstance().connect();
            logger.info("Database have been connected successfully!");
        } catch (SQLException e) {
            logger.error("Error while connecting database!", e);
            System.exit(0);
        } catch (ClassNotFoundException e) {
            logger.error("Database driver was not found!", e);
            System.exit(0);
        }

//        try {
//            DBQueries.initStatements();
//        } catch (SQLException e) {
//            logger.error("Error while preparing statements for database!", e);
//            System.exit(0);
//        }

        collectionController = new CollectionController();
        try {
            collectionController.loadCollection();
        } catch (SQLException e) {
            logger.error("Error while loading collection from database!", e);
        }

        clientCommandsController = new CommandsController();
        clientCommandsController.setCommandsList(
               new ArrayList<>(Arrays.asList(
                        new InfoCommand(collectionController),
                        new ShowCommand(collectionController),
                        new AddCommand(collectionController),
                        new UpdateByIdCommand(collectionController),
                        new RemoveByIdCommand(collectionController),
                        new ClearCommand(collectionController),
                        new RemoveFirstCommand(collectionController),
                        new RemoveGreaterCommand(collectionController),
                        new RemoveLowerCommand(collectionController),
                        new MinBySalaryCommand(collectionController),
                        new FilterLessThanEndDateCommand(collectionController),
                        new PrintFieldDescendingSalaryCommand(collectionController)
                ))
        );
        serverCommandsController = new CommandsController();
        serverCommandsController.setCommandsList(
                new ArrayList<>(Arrays.asList(
                        new SaveCommand(collectionController, dataFileController),
                        new ExitCommand(serverCommandsController),
                        new HelpCommand(serverCommandsController)
                ))
        );

//        try {
//
////            PreparedStatement test = DBQueries.WORKER_INSERT();
////
////            test.setString(1, "popa");
////            test.setDouble(2, 6);
////            test.setInt(3, 15);
////            test.setTimestamp(4, Timestamp.valueOf("2024-04-28 12:00:00"));
////            test.setTimestamp(5, Timestamp.valueOf("2024-04-28 12:00:00"));
////            test.setString(6, "FIRED");
////            test.setInt(7, 5);
////            test.setInt(8, 1);
////
////
////            test.execute();
////            test.close();
//
//            PreparedStatement add_command_qury = DBQueries.ADD_COMMAND();
//
//
//            add_command_qury.setString(1, "polh");
//            add_command_qury.setDouble(2, 15);
//            add_command_qury.setDouble(3, 15);
//            add_command_qury.setInt(4, 15);
//            add_command_qury.setTimestamp(5, Timestamp.valueOf("2024-04-28 12:00:00"));
//            add_command_qury.setTimestamp(6, Timestamp.valueOf("2024-04-28 12:00:00"));
//            add_command_qury.setString(7, "FIRED");
//            add_command_qury.setInt(8, 100);
//            add_command_qury.setString(9, "RED");
//            add_command_qury.setString(10, "USA");
//            add_command_qury.setString(11, "po");
//
//            add_command_qury.execute();
//            add_command_qury.close();
//        } catch (SQLException e) {
//            throw new RuntimeException(e);
//        }
        interactiveMode();
    }

    /**
     * Method to handle server requests
     * <p>It checks two possible sourses of requests: admin console and client
     * <p>If any error is occurred method prints error message and continues to read data
     */
    public static void interactiveMode() {
        while(true) {
            try {
                askClient();
                askAdmin();
            } catch (IOException e) {
                logger.error("An error occurred while reading request!", e);
            }
        }
    }

    /**
     * Method to handle requests from all clients
     * <p>It gets list of all available clients and then call {@link #handleClientRequest(ClientRequest)} method
     * @throws IOException If any I\O error occurred
     * @throws ReceivingDataException If error occurred while receiving data from client
     */
    private static void askClient() throws IOException {
        if(selector.selectNow() == 0) return;
        Set<SelectionKey> keys = selector.selectedKeys();
        for (var iter = keys.iterator(); iter.hasNext();){
            SelectionKey key = iter.next(); iter.remove();
            if(key.isValid()){
                if(key.isReadable()){
                    ClientRequest clientRequest;
                    try {
                        clientRequest = (ClientRequest) server.receiveObject();
                        handleClientRequest(clientRequest);
                    } catch (SendingDataException e) {
                        logger.error("Could not send data to client", e);
                    } catch (ReceivingDataException e) {
                        logger.error("Could not receive data from client", e);
                    }
                }
            }
        }
    }

    /**
     * Method to check if any commands from admin were received
     * <p>If console input is ready, command is read using {@link CommandReader}
     * <p>Then {@link #handleAdminRequest(PackedCommand)} method is called
     * @throws IOException
     */
    private static void askAdmin() throws IOException {
        if(reader.ready()) {
            PackedCommand packedCommand = CommandReader.getInstance().readCommand();
            if(packedCommand != null) handleAdminRequest(packedCommand);
        }
    }

    /**
     * Method to handle commands from admin
     * <p>It creates {@link UserCommand} object, executes it and print result to console
     * @param packedCommand Object which contains all info about command
     */
    private static void handleAdminRequest(PackedCommand packedCommand) {
        UserCommand command;
        try {
            command = serverCommandsController.launchCommand(packedCommand);
        } catch (Exception e) {
            Console.getInstance().printError(e.getMessage());
            return;
        }
        ServerResponse responce = command.execute();
        switch (responce.state()) {
            case SUCCESS:
                Console.getInstance().printLn(responce.data());
                break;
            case EXCEPTION:
                Console.getInstance().printError(((Exception) responce.data()).getMessage());
        }
    }

    /**
     * Method to handle requests from client
     * <p>It check what type of requests is received and then generate and rend answer to client
     * @param clientRequest Request from client
     * @throws IOException If any I\O error occurred while sending answer to client
     */
    private static void handleClientRequest(ClientRequest clientRequest) throws SendingDataException {
        if(clientRequest.getRequestType() != ClientRequestType.LOG_IN &&
           clientRequest.getRequestType() != ClientRequestType.CHECK_USERNAME &&
           clientRequest.getRequestType() != ClientRequestType.SIGN_IN){
           try {
               AuthorizationController.logIn(clientRequest.user());
           } catch (UsernameNotFoundException | WrongPasswordException e) {
               logger.warn("Someone used username '{}' and password '{}' in order to execute query!", clientRequest.user().userName(), clientRequest.user().password());
               return;
           } catch (SQLException e) {
               logger.error("Database error occurred!", e);
               return;
           }
        }
        switch (clientRequest.getRequestType()) {
            case EXECUTE_COMMAND:
                PackedCommand packedCommand = (PackedCommand) clientRequest.data();
                logger.info("Request for executing command {}", packedCommand.commandName());
                ServerResponse serverResponse = null;
                try {
                    UserCommand command = clientCommandsController.launchCommand(packedCommand);
                    serverResponse = command.execute();
                } catch (Exception e) {
                    serverResponse = new ServerResponse(ResultState.EXCEPTION, e);
                } finally {
                    server.sendObject(serverResponse);
                    logger.info("Command {} executed successfully", packedCommand.commandName());
                }
                break;
            case CHECK_ID:
                long id = (long) clientRequest.data();
                logger.info("Request for checking id {}",id);
                server.sendObject(collectionController.containsId(id));
                logger.info("Request handled successfully");
                break;
            case IS_COLLECTION_EMPTY:
                logger.info("Request for checking if collection is empty");
                server.sendObject(collectionController.getCollection().isEmpty());
                logger.info("Request handled successfully");
                break;
            case SIGN_IN:
                UserInfo newUser = (UserInfo) clientRequest.data();
                logger.info("Reques for adding new user with username '{}'", newUser.userName());
                try {
                    AuthorizationController.addUser(newUser);
                    server.sendObject(new ServerResponse(ResultState.SUCCESS, null));
                    logger.info("User '{}' added successfully", newUser.userName());
                } catch (SQLException e) {
                    logger.error("Database error occurred!", e);
                }
                break;
            case LOG_IN:
                UserInfo userInfo = (UserInfo) clientRequest.data();
                logger.info("Login request from user '{}' received", userInfo.userName());
                try {
                    AuthorizationController.logIn(userInfo);
                    server.sendObject(new ServerResponse(ResultState.SUCCESS, null));
                    logger.info("User '{}' logged in successfully", userInfo.userName());
                } catch (SQLException e) {
                    logger.error("Database error occurred!", e);
                } catch (WrongPasswordException | UsernameNotFoundException e) {
                    logger.info("Login for user '{}' was not successful", userInfo.userName());
                    server.sendObject(new ServerResponse(ResultState.EXCEPTION, e));
                }
                break;
            case CHECK_USERNAME:
                String userName = (String) clientRequest.data();
                logger.info("Request for checking username '{}' received", userName);
                try {
                    if(AuthorizationController.checkUsername(userName)){
                        server.sendObject(new ServerResponse(ResultState.EXCEPTION,
                                new UsernameAlreadyExistsException(userName)));
                    }
                    else {
                        server.sendObject(new ServerResponse(ResultState.SUCCESS, null));
                    }
                } catch (SQLException e) {
                    logger.error("Database error occurred!", e);
                }
                break;
        }
    }
}