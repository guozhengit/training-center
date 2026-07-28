package com.guoyongzheng.training.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Connection-scoped persistence for immutable 14-day plan instances and completion progress. */
public final class PlanRepository {

    public void insertDay(Connection connection, PlanDay day) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_days
                    (id, plan_id, day_number, plan_date, theme, completed)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, day.id());
            statement.setString(2, day.planId());
            statement.setInt(3, day.dayNumber());
            statement.setString(4, day.planDate().toString());
            statement.setString(5, day.theme());
            statement.setBoolean(6, day.completed());
            statement.executeUpdate();
        }
    }

    public void insertTask(Connection connection, PlanTask task) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_tasks
                    (id, plan_day_id, task_order, task_type, title,
                     target_count, completed_count, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, task.id());
            statement.setString(2, task.planDayId());
            statement.setInt(3, task.taskOrder());
            statement.setString(4, task.taskType());
            statement.setString(5, task.title());
            statement.setInt(6, task.targetCount());
            statement.setInt(7, task.completedCount());
            statement.setString(8, utc(task.completedAt()));
            statement.executeUpdate();
        }
    }

    public List<PlanDay> findDays(Connection connection, String planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM plan_days
                WHERE plan_id = ?
                ORDER BY day_number
                """)) {
            statement.setString(1, planId);
            try (ResultSet result = statement.executeQuery()) {
                List<PlanDay> days = new ArrayList<>();
                while (result.next()) {
                    days.add(new PlanDay(
                            result.getString("id"),
                            result.getString("plan_id"),
                            result.getInt("day_number"),
                            LocalDate.parse(result.getString("plan_date")),
                            result.getString("theme"),
                            result.getBoolean("completed")));
                }
                return List.copyOf(days);
            }
        }
    }

    public List<PlanTask> findTasks(Connection connection, String planDayId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM plan_tasks
                WHERE plan_day_id = ?
                ORDER BY task_order
                """)) {
            statement.setString(1, planDayId);
            try (ResultSet result = statement.executeQuery()) {
                List<PlanTask> tasks = new ArrayList<>();
                while (result.next()) {
                    tasks.add(new PlanTask(
                            result.getString("id"),
                            result.getString("plan_day_id"),
                            result.getInt("task_order"),
                            result.getString("task_type"),
                            result.getString("title"),
                            result.getInt("target_count"),
                            result.getInt("completed_count"),
                            instant(result.getString("completed_at"))));
                }
                return List.copyOf(tasks);
            }
        }
    }

    public void updateTaskProgress(
            Connection connection, String id, int completedCount, Instant completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE plan_tasks
                SET completed_count = ?, completed_at = ?
                WHERE id = ?
                """)) {
            statement.setInt(1, completedCount);
            statement.setString(2, utc(completedAt));
            statement.setString(3, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Unknown plan task: " + id);
            }
        }
    }

    private static String utc(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record PlanDay(
            String id,
            String planId,
            int dayNumber,
            LocalDate planDate,
            String theme,
            boolean completed) {
    }

    public record PlanTask(
            String id,
            String planDayId,
            int taskOrder,
            String taskType,
            String title,
            int targetCount,
            int completedCount,
            Instant completedAt) {
    }
}
